from __future__ import annotations
import hashlib
import json
import os
from pathlib import Path
import tempfile
import ctypes
import errno
from contextlib import contextmanager

CHUNK = 1024 * 1024
MAX_MANIFEST = 1024 * 1024


def sha_file(path: Path, ceiling: int = 16 * 1024**3) -> dict:
    """Hash a held descriptor and reject changes/replacements while reading."""
    with path.open('rb') as stream:
        before = os.fstat(stream.fileno())
        if not 0 <= before.st_size <= ceiling:
            raise ValueError('Artifact exceeds its byte budget')
        digest = hashlib.sha256(); total = 0
        while block := stream.read(CHUNK):
            total += len(block)
            if total > ceiling:
                raise ValueError('Artifact grew beyond its byte budget')
            digest.update(block)
        after = os.fstat(stream.fileno()); current = path.stat()
        stamp = lambda value: (value.st_dev, value.st_ino, value.st_size, value.st_mtime_ns, value.st_ctime_ns)
        if stamp(before) != stamp(after) or stamp(after) != stamp(current) or total != after.st_size:
            raise ValueError('Artifact changed while reading')
    return {'sha256': digest.hexdigest(), 'bytes': total}


def canonical(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')) + '\n').encode('utf-8')


def read_manifest(path: Path) -> dict:
    if path.stat().st_size > MAX_MANIFEST:
        raise ValueError('Manifest exceeds 1 MiB')
    with path.open('rb') as stream:
        raw = stream.read(MAX_MANIFEST + 1)
    if len(raw) > MAX_MANIFEST:
        raise ValueError('Manifest grew beyond 1 MiB')
    value = json.loads(raw.decode('utf-8'))
    if not isinstance(value, dict):
        raise ValueError('Manifest must be a JSON object')
    return value


def write_json(path: Path, value: object) -> None:
    encoded = canonical(value)
    if len(encoded) > MAX_MANIFEST:raise ValueError('Generated manifest exceeds its 1 MiB budget')
    with path.open('xb') as stream:
        stream.write(encoded); stream.flush(); os.fsync(stream.fileno())


def local_file(root: Path, relative: str) -> Path:
    candidate = Path(relative)
    if candidate.is_absolute() or not candidate.parts or any(part in {'.', '..'} for part in candidate.parts):
        raise ValueError('Input paths must stay inside the source manifest directory')
    root = root.resolve(strict=True)
    current = root
    for part in candidate.parts:
        current /= part
        if current.is_symlink():
            raise ValueError('Source artifacts may not be symbolic links')
    result = current.resolve(strict=True)
    if not result.is_relative_to(root) or not result.is_file():
        raise ValueError('Input is not a managed regular file')
    return result


@contextmanager
def output_directory(destination: Path):
    """Publish a complete run directory once; failed runs never look complete."""
    destination = destination.absolute()
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        raise FileExistsError('Choose a new output directory; existing runs are preserved')
    staging = Path(tempfile.mkdtemp(prefix='.' + destination.name + '.pending-', dir=destination.parent))
    try:
        yield staging
        if destination.exists():
            raise FileExistsError('Output appeared while the run was in progress')
        publish_new_directory(staging, destination)
    except BaseException as failure:
        # Failed local runs remain inspectable and cannot be mistaken for completed artifacts.
        if staging.exists():
            try:
                write_json(staging / 'run-status.json', {'status': 'CANCELLED' if isinstance(failure, KeyboardInterrupt) else 'FAILED', 'error_class': type(failure).__name__})
                publish_new_directory(staging, destination)
            except BaseException:
                pass  # Preserve the distinct pending directory if a conflicting destination appeared.
        raise


def publish_new_directory(staging: Path, destination: Path) -> None:
    # Linux renameat2 provides the no-replacement guarantee absent from os.rename.
    native = ctypes.CDLL(None, use_errno=True)
    function = getattr(native, 'renameat2', None)
    if function is None:
        raise RuntimeError('Atomic output publication requires Linux renameat2; pending artifacts are preserved')
    function.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
    function.restype = ctypes.c_int
    if function(-100, os.fsencode(staging), -100, os.fsencode(destination), 1) != 0:
        code = ctypes.get_errno()
        raise OSError(code, os.strerror(code))
    descriptor = os.open(destination.parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def directory_receipt(root: Path) -> dict:
    root = root.resolve(strict=True)
    items = []
    for path in sorted(root.rglob('*')):
        if path.is_symlink():
            raise ValueError('Model directory contains a symbolic link')
        if path.is_file():
            if len(items) >= 4096:raise ValueError('Model directory exceeds its bounded file inventory')
            items.append({'path': path.relative_to(root).as_posix(), **sha_file(path)})
    if not items:
        raise ValueError('Model directory is empty')
    return {'files': items, 'manifest_sha256': hashlib.sha256(canonical(items)).hexdigest()}


def dataset_files(manifest: Path) -> tuple[dict, Path, Path]:
    value = read_manifest(manifest)
    if value.get('schema') != 'orez-training-dataset-v1' or value.get('status') != 'PREPARED':
        raise ValueError('Expected a completed dataset preparation manifest')
    files = []
    for split in ('train', 'held_out'):
        item = value['splits'][split]
        path = local_file(manifest.parent, item['file'])
        if sha_file(path) != {'sha256': item['sha256'], 'bytes': item['bytes']} or item['records'] <= 0:
            raise ValueError('Dataset split changed or is empty')
        files.append(path)
    return value, files[0], files[1]


def code_receipt() -> dict:
    root = Path(__file__).parent
    # Interpreter bytecode/cache state is not a source identity.
    files = [{'path': path.name, **sha_file(path)} for path in sorted(root.glob('*.py'))]
    return {'files': files, 'manifest_sha256': hashlib.sha256(canonical(files)).hexdigest()}
