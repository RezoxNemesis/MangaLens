#!/usr/bin/env python3
"""Inspect compiled JUnit4 methods without loading Android classes into the host JVM."""
from pathlib import Path
import io
import json
import struct
import sys


def audit(path):
    data = io.BytesIO(path.read_bytes())
    def u1(): return struct.unpack('>B', data.read(1))[0]
    def u2(): return struct.unpack('>H', data.read(2))[0]
    def u4(): return struct.unpack('>I', data.read(4))[0]
    assert u4() == 0xCAFEBABE
    data.read(4)
    count = u2(); pool = [None] * count; index = 1
    while index < count:
        tag = u1()
        if tag == 1: pool[index] = data.read(u2()).decode('utf-8', errors='replace')
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18): data.read(4)
        elif tag in (5, 6): data.read(8); index += 1
        elif tag in (7, 8, 16, 19, 20): data.read(2)
        elif tag == 15: data.read(3)
        else: raise ValueError(f'Unknown constant-pool tag {tag}')
        index += 1
    data.read(6); data.read(2 * u2())
    def attributes():
        result = []
        for _ in range(u2()):
            name = pool[u2()]; payload = data.read(u4()); result.append((name, payload))
        return result
    for _ in range(u2()): data.read(6); attributes()
    result = []
    for _ in range(u2()):
        flags, name, descriptor = u2(), pool[u2()], pool[u2()]
        for attribute, payload in attributes():
            if attribute != 'RuntimeVisibleAnnotations': continue
            offset = 0
            def take():
                nonlocal offset
                value = struct.unpack_from('>H', payload, offset)[0]; offset += 2; return value
            def annotation():
                kind = pool[take()]
                for _ in range(take()): take(); element()
                return kind
            def element():
                nonlocal offset
                kind = chr(payload[offset]); offset += 1
                if kind == '@': annotation()
                elif kind == '[':
                    for _ in range(take()): element()
                elif kind == 'e': take(); take()
                else: take()
            for _ in range(take()):
                if annotation() == 'Lorg/junit/Test;':
                    result.append({'classfile': str(path), 'method': name, 'descriptor': descriptor,
                                   'valid': descriptor == '()V' and bool(flags & 1) and not bool(flags & 8)})
    return result


methods = [method for folder in sys.argv[1:] for path in Path(folder).rglob('*.class') for method in audit(path)]
invalid = [method for method in methods if not method['valid']]
print(json.dumps({'test_methods': len(methods), 'invalid': invalid}, indent=2))
sys.exit(1 if invalid else 2 if not methods else 0)
