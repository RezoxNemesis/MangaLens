from __future__ import annotations
import hashlib
import json
import os
import re
import sqlite3
import unicodedata
from pathlib import Path
from .common import canonical, local_file, output_directory, read_manifest, sha_file, write_json, code_receipt

ALLOWED = {'MIT', 'Apache-2.0', 'CC0-1.0', 'CC-BY-4.0', 'CC-BY-SA-4.0', 'Public-Domain', 'Authored-Permission'}
MAX_ROW = 128 * 1024
MAX_TEXT = 20_000
PRIVATE = re.compile(r'\b(?:[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}|api[_ -]?key|private key|access token|aadhaar|social security|passport number)\b', re.I)


def clean(value: object) -> str:
    if not isinstance(value, str):
        return ''
    return re.sub(r'\s+', ' ', unicodedata.normalize('NFKC', value)).strip()


def find(db, group):
    visited = []
    while True:
        parent = db.execute('SELECT parent FROM groups WHERE key=?', (group,)).fetchone()[0]
        if parent == group:
            break
        visited.append(group); group = parent
        if len(visited) > 10_000:
            raise ValueError('Group lineage exceeds bounded depth')
    for child in visited:
        db.execute('UPDATE groups SET parent=? WHERE key=?', (group, child))
    return group


def unite(db, left, right):
    left, right = find(db, left), find(db, right)
    if left != right:
        low, high = sorted((left, right))
        db.execute('UPDATE groups SET parent=? WHERE key=?', (low, high))


def build_dataset(args):
    policy = read_manifest(args.sources)
    sources = policy.get('sources')
    if policy.get('schema') != 'orez-training-sources-v1' or not isinstance(sources, list) or not 1 <= len(sources) <= 256:
        raise ValueError('Expected 1–256 explicit licensed source receipts')
    if not 1 <= args.held_out_percent <= 50:
        raise ValueError('Held-out fraction must be 1–50 percent')
    ids = set(); lineage = []
    for source in sources:
        identity = source.get('id', '')
        if not isinstance(identity, str) or not 1 <= len(identity) <= 128 or identity in ids:
            raise ValueError('Source IDs must be distinct and bounded')
        ids.add(identity)
        if source.get('license') not in ALLOWED or source.get('training_permitted') is not True:
            raise ValueError('Training permission and license evidence are required for every source')
        if not isinstance(source.get('revision'), str) or not 1 <= len(source['revision']) <= 160:
            raise ValueError('Source revision is missing')
        data = local_file(args.sources.parent, source['file'])
        license_path = local_file(args.sources.parent, source['license_file'])
        actual_license = sha_file(license_path, 1024 * 1024)
        if actual_license['bytes'] == 0 or actual_license['sha256'] != source.get('license_sha256'):
            raise ValueError('License document does not match its receipt')
        if not re.fullmatch('[a-f0-9]{64}', source.get('sha256', '')):
            raise ValueError('Every input needs its expected complete SHA-256')
        lineage.append({'id': identity, 'revision': source['revision'], 'license': source['license'],
            'attribution': str(source.get('attribution', identity))[:2048], 'license_document': actual_license,
            'expected_sha256': source['sha256'], 'path': data, 'license_path': license_path})
    with output_directory(args.output) as out:
        db = sqlite3.connect(out / 'preparation.sqlite')
        try:
            db.execute('CREATE TABLE groups(key TEXT PRIMARY KEY,parent TEXT NOT NULL)')
            db.execute('CREATE TABLE records(key TEXT PRIMARY KEY,group_key TEXT NOT NULL,body BLOB NOT NULL)')
            db.execute('CREATE TABLE prompt_groups(key TEXT PRIMARY KEY,group_key TEXT NOT NULL)')
            db.execute('CREATE TABLE contributors(case_key TEXT,source_id TEXT,revision TEXT,license_sha TEXT,PRIMARY KEY(case_key,source_id))')
            counts = {'accepted': 0, 'duplicates': 0, 'filtered': 0}
            receipts = []
            for source in lineage:
                digest = hashlib.sha256(); total = 0
                with source['path'].open('rb') as stream:
                    before = os.fstat(stream.fileno())
                    while line := stream.readline(MAX_ROW + 1):
                        digest.update(line); total += len(line)
                        if total > 10 * 1024**3:
                            raise ValueError('Source exceeds 10 GiB')
                        if len(line) > MAX_ROW:
                            while line and not line.endswith(b'\n'):
                                line = stream.readline(MAX_ROW + 1); digest.update(line); total += len(line)
                                if total > 10 * 1024**3: raise ValueError('Source exceeds 10 GiB')
                            counts['filtered'] += 1; continue
                        try:
                            raw = json.loads(line.decode('utf-8'))
                        except (ValueError, UnicodeError):
                            counts['filtered'] += 1; continue
                        if not isinstance(raw, dict): counts['filtered'] += 1; continue
                        prompt, response = clean(raw.get('prompt')), clean(raw.get('response'))
                        group = clean(raw.get('group'))
                        if not group or len(group) > 512 or not prompt or not response or len(prompt) + len(response) > MAX_TEXT or PRIVATE.search(prompt + ' ' + response):
                            counts['filtered'] += 1; continue
                        # Shared namespaced series/conversation group identifiers are supplied explicitly.
                        group_key = hashlib.sha256(group.encode()).hexdigest()
                        key = hashlib.sha256(canonical({'prompt': prompt, 'response': response})).hexdigest()
                        db.execute('INSERT OR IGNORE INTO groups VALUES(?,?)', (group_key, group_key))
                        prompt_key = hashlib.sha256(prompt.casefold().encode()).hexdigest()
                        existing_prompt = db.execute('SELECT group_key FROM prompt_groups WHERE key=?', (prompt_key,)).fetchone()
                        if existing_prompt: unite(db, existing_prompt[0], group_key)
                        else: db.execute('INSERT INTO prompt_groups VALUES(?,?)', (prompt_key, group_key))
                        db.execute('INSERT OR IGNORE INTO contributors VALUES(?,?,?,?)', (key, source['id'], source['revision'], source['license_document']['sha256']))
                        old = db.execute('SELECT group_key FROM records WHERE key=?', (key,)).fetchone()
                        if old:
                            unite(db, old[0], group_key); counts['duplicates'] += 1; continue
                        record = {'case_id': key, 'group_id': group_key, 'prompt': prompt, 'response': response,
                            'language': clean(raw.get('language', 'und'))[:32], 'category': clean(raw.get('category', 'general'))[:128],
                            'source_id': source['id'], 'source_revision': source['revision'], 'license': source['license'],
                            'license_document_sha256': source['license_document']['sha256']}
                        db.execute('INSERT INTO records VALUES(?,?,?)', (key, group_key, canonical(record)))
                        counts['accepted'] += 1
                        if counts['accepted'] % 1000 == 0: db.commit()
                    after = os.fstat(stream.fileno()); current = source['path'].stat()
                    stamp = lambda s: (s.st_dev,s.st_ino,s.st_size,s.st_mtime_ns,s.st_ctime_ns)
                    if stamp(before) != stamp(after) or stamp(after) != stamp(current) or total != after.st_size:
                        raise ValueError('Source changed during preparation')
                if digest.hexdigest() != source['expected_sha256']:
                    raise ValueError('Input bytes do not match immutable source receipt')
                receipts.append({k:v for k,v in source.items() if k not in {'path','license_path','expected_sha256'}} | {'input': {'sha256':digest.hexdigest(),'bytes':total}})
                db.commit()
            if counts['accepted'] < 2:
                raise ValueError('At least two distinct accepted cases are required')
            split_counts = {'train': 0, 'held_out': 0}; split_hashes = {key:hashlib.sha256() for key in split_counts}; split_bytes = dict.fromkeys(split_counts,0)
            handles = {key:(out / (key + '.jsonl')).open('xb') for key in split_counts}
            try:
                for key, group, body in db.execute('SELECT key,group_key,body FROM records ORDER BY key'):
                    root_group = find(db, group)
                    bucket = int(hashlib.sha256(canonical({'seed':args.seed,'group':root_group})).hexdigest(),16) % 100
                    split = 'held_out' if bucket < args.held_out_percent else 'train'
                    value = json.loads(body); value['group_id'] = root_group
                    value['lineage'] = [{'source_id':identity,'source_revision':revision,'license_document_sha256':license_hash} for identity,revision,license_hash in db.execute('SELECT source_id,revision,license_sha FROM contributors WHERE case_key=? ORDER BY source_id',(key,))]
                    encoded = canonical(value)
                    handles[split].write(encoded); split_hashes[split].update(encoded); split_bytes[split] += len(encoded); split_counts[split] += 1
                for stream in handles.values(): stream.flush(); os.fsync(stream.fileno())
            finally:
                for stream in handles.values(): stream.close()
            if not all(split_counts.values()):
                raise ValueError('Grouping produced an empty split; add independent groups or choose another seed')
            license_out=out/'licenses';license_out.mkdir()
            for source in lineage:
                with source['license_path'].open('rb') as license_stream:
                    license_body=license_stream.read(1024 * 1024 + 1)
                if len(license_body)>1024 * 1024:raise ValueError('License grew beyond its byte budget')
                if hashlib.sha256(license_body).hexdigest()!=source['license_document']['sha256']:
                    raise ValueError('License changed before export')
                (license_out/(hashlib.sha256(source['id'].encode()).hexdigest()+'.txt')).write_bytes(license_body)
            manifest={'schema':'orez-training-dataset-v1','status':'PREPARED','seed':args.seed,'held_out_percent':args.held_out_percent,
                'grouping':'explicit-group-prompt-and-content-overlap-union-v1','content_identity':'NFKC-whitespace-normalized-prompt-response-v1',
                'counts':counts,'sources':receipts,'code':code_receipt(),
                'splits':{key:{'file':key+'.jsonl','records':split_counts[key],'bytes':split_bytes[key],'sha256':split_hashes[key].hexdigest()} for key in split_counts},
                'qualification':'NONE; preparation is not training or model-quality evidence'}
            write_json(out/'dataset-manifest.json',manifest)
        finally:
            db.close()
        (out/'preparation.sqlite').unlink()
