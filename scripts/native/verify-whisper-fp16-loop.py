#!/usr/bin/env python3
"""Verify the pinned FP16 loop patch and build exact Android-target kernel controls.

This runs only isolated primitive kernels on an x86_64 Linux host. It also builds
an Android PIE for a separate device run; neither result substitutes for actual
Whisper audio accuracy, cancellation or live-caption deadline acceptance.
"""
import argparse
import hashlib
import json
import platform
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import tempfile

INPUT_SHA = '7725a5af7d4dc0f9487f1fdd83ba4dd6b0402bf5295d5782f16f6b9a991f9969'
OUTPUT_SHA = '73a9e796a187556c6e653198c05f41ee67b4d995579f06af701713559fea0723'
HEADERS = {
    'ggml/src/ggml-cpu/vec.h': '9115dd2a255556fc737a40ccf0fe0f1a1cc581600d649689db714b4cefeba0e0',
    'ggml/src/ggml-cpu/simd-mappings.h': 'b6e12bfbbcc8fe80821e1bccc93af9b782623f3896f2b756148ba262884f23b3',
    'ggml/src/ggml-impl.h': '84f98c994ab344d8f9939daffcdc57efdae87ead8271a14cfe2a8c43717d168b',
    'ggml/include/ggml.h': '58d10566829d3aa4e6ca1ac73e69ecd0293b1c219a3dabd7ad836c49c4e2b64e',
}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def run(command, *, cwd=None, allowed=(0,), timeout=60):
    result = subprocess.run(command, cwd=cwd, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=timeout)
    require(result.returncode in allowed,
            f'Command failed ({result.returncode}): {shlex.join(command)}\n{result.stdout}')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True, help='Pinned fetched whisper.cpp source')
    parser.add_argument('--compile-commands', type=Path, required=True, help='Actual x86_64 Android CMake compile_commands.json')
    parser.add_argument('--cmake', default='cmake')
    parser.add_argument('--output', type=Path, required=True, help='New writable evidence directory')
    parser.add_argument('--skip-host-run', action='store_true')
    args = parser.parse_args()
    repository = Path(__file__).resolve().parents[2]
    native = repository / 'whisper-native/src/main/cpp'
    source = args.source.resolve()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    records = []
    for path, expected in HEADERS.items():
        require(sha(source / path) == expected, f'Pinned header differs: {path}')
    input_file = source / 'ggml/src/ggml-cpu/vec.cpp'
    input_sha = sha(input_file)
    require(input_sha in (INPUT_SHA, OUTPUT_SHA), 'Pinned vector input differs')
    baseline_root = output / 'baseline'
    baseline_vec = baseline_root / 'ggml/src/ggml-cpu/vec.cpp'
    baseline_vec.parent.mkdir(parents=True)
    shutil.copyfile(input_file, baseline_vec)
    run(['git', 'init', '--quiet', str(baseline_root)])
    if input_sha == OUTPUT_SHA:
        run(['git', 'apply', '--reverse', '--whitespace=error-all', str(native / 'whisper-f16-loop-unroll.patch')], cwd=baseline_root)
    require(sha(baseline_vec) == INPUT_SHA, 'Baseline reconstruction differs')
    candidate_root = output / 'candidate'
    candidate_vec = candidate_root / 'ggml/src/ggml-cpu/vec.cpp'
    candidate_vec.parent.mkdir(parents=True)
    shutil.copyfile(baseline_vec, candidate_vec)
    run(['git', 'init', '--quiet', str(candidate_root)])
    command = [args.cmake, '-DWHISPER_SOURCE_DIR=' + str(candidate_root), '-P', str(native / 'patch_f16_loop_unroll.cmake')]
    run(command)
    require(sha(candidate_vec) == OUTPUT_SHA, 'Patched vector output differs')
    run(command)
    require(sha(candidate_vec) == OUTPUT_SHA, 'Idempotent run changed the vector')
    records.append({'case': 'pinned_input_and_idempotent_output', 'result': 'PASS'})
    for case, use_patched, tamper_patch in [('source_drift', False, False), ('patch_drift', False, True), ('patch_drift_after_apply', True, True)]:
        with tempfile.TemporaryDirectory(prefix=case + '-', dir=output) as temp:
            root = Path(temp)
            vec = root / 'ggml/src/ggml-cpu/vec.cpp'
            vec.parent.mkdir(parents=True)
            original = candidate_vec.read_bytes() if use_patched else baseline_vec.read_bytes()
            vec.write_bytes(original if tamper_patch else original + b'\n')
            before = vec.read_bytes()
            script_root = root / 'patch'
            script_root.mkdir()
            for name in ('patch_f16_loop_unroll.cmake', 'whisper-f16-loop-unroll.patch'):
                shutil.copyfile(native / name, script_root / name)
            if tamper_patch:
                patch = script_root / 'whisper-f16-loop-unroll.patch'
                patch.write_bytes(patch.read_bytes() + b'\n')
            result = run([args.cmake, '-DWHISPER_SOURCE_DIR=' + str(root), '-P', str(script_root / 'patch_f16_loop_unroll.cmake')], allowed=(1,))
            require(vec.read_bytes() == before, f'{case} mutated unverified source')
            records.append({'case': case, 'result': 'PASS', 'rejection': result.stdout.strip()})
    rows = json.loads(args.compile_commands.read_text())
    row = next(row for row in rows if row['file'].endswith('/ggml-cpu/vec.cpp'))
    require(Path(row['file']).resolve() == input_file.resolve(), 'Actual compile command must use the supplied pinned source')
    command = shlex.split(row['command'])
    require(any(arg.startswith('--target=x86_64') and 'android' in arg for arg in command), 'Actual Android x86_64 command required')
    require('-O2' in command and '-msse4.2' in command, 'Actual O2/SSE4.2 command required')
    forbidden = ('-O3', '-Ofast', '-ffast-math', '-march=native', '-mavx', '-mavx2', '-mfma', '-mf16c', '-mbmi2')
    require(not any(arg in forbidden for arg in command), 'Actual command enables an unsupported experiment')
    binary = Path(command[0]).parent
    objects = []
    for name, vec in [('baseline', baseline_vec), ('unrolled', candidate_vec)]:
        compile_command = command.copy()
        obj = output / (name + '-android-vec.o')
        compile_command[compile_command.index('-o') + 1] = str(obj)
        compile_command[compile_command.index('-c') + 1] = str(vec)
        result = run(compile_command, cwd=row.get('directory'))
        (output / (name + '-compile.txt')).write_text(result.stdout)
        disassembly = run([str(binary / 'llvm-objdump'), '--disassemble-symbols=ggml_vec_dot_f16', '--no-show-raw-insn', '--x86-asm-syntax=intel', str(obj)]).stdout
        (output / (name + '-f16-dot.txt')).write_text(disassembly)
        mulps = len(re.findall(r'\bmulps\b', disassembly))
        sse_stack_operands = len(re.findall(r'\b(?:addps|movaps|mulps|movups)\b[^\n]*\[(?:rbp|rsp)\b', disassembly))
        require(sse_stack_operands > 0 if name == 'baseline' else sse_stack_operands == 0, f'{name}: FP16 accumulator stack behavior changed; review the actual disassembly')
        require(mulps == (1 if name == 'baseline' else 8), f'{name}: fixed inner-loop codegen changed; review the actual disassembly')
        # Rename only defined globals so both complete Android-target objects can
        # be linked into one primitive harness. Undefined dependencies stay real.
        stem = 'old' if name == 'baseline' else 'new'
        symbols = run([str(binary / 'llvm-nm'), '--defined-only', '--extern-only', str(obj)]).stdout
        renamed = output / (name + '-renamed.o')
        rename = []
        for line in symbols.splitlines():
            symbol = line.split()[-1]
            replacement = 'dot_' + stem if symbol == 'ggml_vec_dot_f16' else stem + '_' + symbol
            rename += ['--redefine-sym', symbol + '=' + replacement]
        run([str(binary / 'llvm-objcopy'), *rename, str(obj), str(renamed)])
        objects.append(str(renamed))
        records.append({'case': name, 'command': compile_command, 'object_sha256': sha(obj), 'static_mulps': mulps, 'sse_stack_operand_count': sse_stack_operands, 'result': 'PASS'})
    flags = ['-O2', '-g0', '-std=c++17', '-msse4.2', '-mno-avx', '-mno-avx2', '-mno-fma', '-mno-f16c', '-mno-bmi2', '-fPIC', '-ffunction-sections', '-fdata-sections', '-fstack-protector-strong']
    includes = ['-I' + str(source / 'ggml/src/ggml-cpu'), '-I' + str(source / 'ggml/src'), '-I' + str(source / 'ggml/include')]
    harness = str(Path(__file__).with_name('whisper-fp16-kernel-test.cpp'))
    android = output / 'android-f16-kernel-test'
    target = next(arg for arg in command if arg.startswith('--target='))
    sysroot = next(arg for arg in command if arg.startswith('--sysroot='))
    android_command = [command[0], target, sysroot, *flags, '-fPIE', '-static-libstdc++', *includes, harness, *objects, '-Wl,--gc-sections', '-o', str(android)]
    run(android_command)
    records.append({'case': 'android_pie_link', 'command': android_command, 'sha256': sha(android), 'result': 'PASS', 'device_run': 'NOT_RUN'})
    if not args.skip_host_run:
        require(platform.system() == 'Linux' and platform.machine() == 'x86_64', 'Isolated Android-object primitive execution needs x86_64 Linux')
        host = output / 'host-f16-kernel-test'
        host_command = [command[0], *flags, *includes, harness, *objects, '-Wl,--gc-sections', '-o', str(host)]
        run(host_command)
        result = run([str(host)])
        (output / 'host-kernel-output.txt').write_text(result.stdout)
        print(result.stdout, end='')
        records.append({'case': 'isolated_android_object_host_execution', 'command': host_command, 'result': 'PASS', 'scope': 'Primitive bits and host timing only; no Android/model/live deadline claim'})
    (output / 'results.json').write_text(json.dumps(records, indent=2) + '\n')
    print('Patch guards, pinned source and Android kernel controls verified. Device/audio acceptance remains separate.')


if __name__ == '__main__':
    main()
