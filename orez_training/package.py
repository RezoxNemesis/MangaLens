from __future__ import annotations
import hashlib
import json
import os
from pathlib import Path
import zipfile
from .common import canonical, code_receipt, output_directory, sha_file, write_json

ROLES={'DATASET_MANIFEST','TRAINING_MANIFEST','EVALUATION_REPORT','BASE_LICENSE','OUTPUT_LICENSE','CODE_MANIFEST','QUANTIZATION_MANIFEST','MERGE_MANIFEST'}
MAX_ARTIFACT=1024*1024
MAX_TOTAL=16*1024*1024


def package_model(args):
    """Produce the advanced Android Lab evidence ZIP; weights never enter this ZIP."""
    with args.weights.open('rb') as stream:
        if stream.read(4)!=b'GGUF':raise ValueError('Package weight identity must refer to an actual GGUF file')
    weight=sha_file(args.weights)
    bodies={};roles=set(); evaluation_runtime=None; decoded_roles={}
    for supplied in args.artifact:
        role, separator, path=supplied.partition('=')
        if not separator or role not in ROLES or role=='CODE_MANIFEST' or role in roles:
            raise ValueError('Use one ROLE=local-file per recognized evidence role; code identity is generated')
        file=Path(path)
        identity=sha_file(file,MAX_ARTIFACT)
        with file.open('rb') as stream: body=stream.read(MAX_ARTIFACT+1)
        if not body or len(body)>MAX_ARTIFACT or hashlib.sha256(body).hexdigest()!=identity['sha256']:
            raise ValueError('Evidence changed or exceeded its byte budget')
        if role.endswith('MANIFEST') or role=='EVALUATION_REPORT':
            decoded=json.loads(body.decode('utf-8'))
            if not isinstance(decoded,dict):raise ValueError('Evidence manifest must be an object')
            decoded_roles[role]=decoded
            if role=='EVALUATION_REPORT' and (decoded.get('runtime')!='HOST_TRANSFORMERS' or decoded.get('android_qualification')!='PENDING'):
                raise ValueError('Host evaluation must retain its actual host scope and pending Android qualification')
            if role=='EVALUATION_REPORT':
                if decoded.get('schema')!='orez-host-evaluation-v1':raise ValueError('Expected actual supported Transformers evaluation schema')
                if decoded.get('status')!='COMPLETED':raise ValueError('Package the actual completed host evaluation report')
                evaluation_runtime=decoded['runtime']
        suffix='.txt' if role.endswith('LICENSE') else '.json'
        name='artifacts/'+role.lower()+suffix; bodies[name]=(role,body);roles.add(role)
    if not {'DATASET_MANIFEST','BASE_LICENSE','OUTPUT_LICENSE','EVALUATION_REPORT','QUANTIZATION_MANIFEST'}.issubset(roles):raise ValueError('Dataset provenance, completed host evaluation, quantization lineage and actual base/output license texts are required')
    quantization=decoded_roles['QUANTIZATION_MANIFEST'];evaluation=decoded_roles['EVALUATION_REPORT']
    if quantization.get('schema')!='orez-host-quantization-v1' or quantization.get('status')!='COMPLETED' or quantization.get('android_qualification')!='PENDING' or quantization.get('output')!=weight:
        raise ValueError('Quantization receipt does not describe the supplied complete GGUF')
    if quantization.get('base')!=evaluation.get('model'):
        raise ValueError('Host evaluation did not evaluate the quantization source weights')
    dataset_body=bodies['artifacts/dataset_manifest.json'][1]
    dataset=decoded_roles['DATASET_MANIFEST']
    if dataset.get('schema')!='orez-training-dataset-v1' or dataset.get('status')!='PREPARED':raise ValueError('Dataset preparation provenance is incomplete')
    if evaluation.get('dataset_manifest')!={'sha256':hashlib.sha256(dataset_body).hexdigest(),'bytes':len(dataset_body)}:
        raise ValueError('Evaluation dataset lineage does not match the packaged dataset manifest')
    if evaluation.get('held_out')!=dataset.get('splits',{}).get('held_out') or not evaluation.get('held_out'):raise ValueError('Evaluation held-out scope does not match dataset preparation')
    merge=decoded_roles.get('MERGE_MANIFEST');training=decoded_roles.get('TRAINING_MANIFEST')
    if training is not None and (merge is None or training.get('schema')!='orez-host-training-v1' or training.get('status')!='COMPLETED' or training.get('dataset_manifest')!=evaluation.get('dataset_manifest')):
        raise ValueError('Training evidence requires its actual merge and matching dataset lineage')
    if merge is not None and (merge.get('schema')!='orez-host-merge-v1' or merge.get('status')!='COMPLETED' or merge.get('output')!=evaluation.get('model')):
        raise ValueError('Merge receipt does not describe the evaluated source weights')
    if merge is not None and training is not None and (merge.get('adapter')!=training.get('output') or merge.get('base')!=training.get('base')):
        raise ValueError('Merged adapter/base lineage does not match the training run')
    bodies['artifacts/code_manifest.json']=('CODE_MANIFEST',canonical(code_receipt()))
    if len(bodies)>1024 or any(len(body)>MAX_ARTIFACT for _,body in bodies.values()) or sum(len(body) for _,body in bodies.values())>MAX_TOTAL:
        raise ValueError('Evidence packet exceeds Android Lab bounds')
    manifest={'schema':1,'kind':'OREZ_LOCAL_TRAINING_EVIDENCE',
        'model':{'model_id':'local-output-'+weight['sha256'][:24],'sha256':weight['sha256'],'bytes':weight['bytes']},
        'runtime':evaluation_runtime,'android_qualification':'PENDING',
        'artifacts':[{'path':name,'role':role,'bytes':len(body),'sha256':hashlib.sha256(body).hexdigest()} for name,(role,body) in sorted(bodies.items())]}
    with output_directory(args.output) as out:
        archive=out/'orez-lab-evidence.zip'
        with zipfile.ZipFile(archive,'x',compression=zipfile.ZIP_STORED) as packed:
            # Native importer sees the bounded manifest before allocating artifact bodies.
            packed.writestr('lab-manifest.json',canonical(manifest))
            for name,(_,body) in sorted(bodies.items()):packed.writestr(name,body)
        write_json(out/'package-receipt.json',{'schema':'orez-lab-package-receipt-v1','status':'PREPARED','packet':sha_file(archive,MAX_TOTAL+MAX_ARTIFACT),'weights':weight,'android_qualification':'PENDING','activation':'NONE; this ZIP contains provenance and evaluation evidence only'})
