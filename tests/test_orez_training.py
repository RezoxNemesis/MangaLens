"""Authored implementation-phase controls; intentionally not executed yet."""
import hashlib
import json
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from orez_training.common import canonical, read_manifest, sha_file
from orez_training.dataset import build_dataset
from orez_training.package import package_model


class OfflineTrainingContracts(unittest.TestCase):
    def sources(self, root, records, expected=None):
        (root/'permission.txt').write_text('Permission to use these authored fixtures for testing.\n')
        (root/'data.jsonl').write_bytes(b''.join(canonical(row) for row in records))
        manifest={'schema':'orez-training-sources-v1','sources':[{'id':'test-authored','file':'data.jsonl','sha256':expected or sha_file(root/'data.jsonl')['sha256'],'revision':'fixture-v1','license':'Authored-Permission','license_file':'permission.txt','license_sha256':sha_file(root/'permission.txt')['sha256'],'training_permitted':True}]}
        (root/'sources.json').write_bytes(canonical(manifest))
        return SimpleNamespace(sources=root/'sources.json',output=root/'dataset',seed=42,held_out_percent=20)

    def records(self):
        return [{'prompt':f'Unique question {i}','response':f'Authored answer {i}','group':f'conversation-{i}'} for i in range(40)]

    def test_shared_prompt_different_reference_never_crosses_group_split(self):
        with TemporaryDirectory() as directory:
            root=Path(directory);records=self.records()+[{'prompt':'Same input','response':'Reference A','group':'alpha'},{'prompt':'Same input','response':'Reference B','group':'beta'}]
            build_dataset(self.sources(root,records))
            splits={key:[json.loads(line) for line in (root/'dataset'/f'{key}.jsonl').read_text().splitlines()] for key in ('train','held_out')}
            left={row['prompt'].casefold() for row in splits['train']};right={row['prompt'].casefold() for row in splits['held_out']}
            self.assertFalse(left&right)
            chosen=[key for key,rows in splits.items() if any(row['prompt']=='Same input' for row in rows)]
            self.assertEqual(1,len(chosen));self.assertEqual(2,sum(row['prompt']=='Same input' for row in splits[chosen[0]]))

    def test_changed_input_receipt_preserves_failed_run_without_prepared_manifest(self):
        with TemporaryDirectory() as directory:
            root=Path(directory)
            with self.assertRaises(ValueError):build_dataset(self.sources(root,self.records(),expected='0'*64))
            self.assertEqual('FAILED',read_manifest(root/'dataset'/'run-status.json')['status'])
            self.assertFalse((root/'dataset'/'dataset-manifest.json').exists())

    def test_existing_output_is_preserved_before_dataset_work(self):
        with TemporaryDirectory() as directory:
            root=Path(directory);args=self.sources(root,self.records());args.output.mkdir();(args.output/'valuable.txt').write_text('Keep this')
            with self.assertRaises(FileExistsError):build_dataset(args)
            self.assertEqual('Keep this',(args.output/'valuable.txt').read_text())

    def test_metadata_hash_cannot_attach_an_unrelated_evaluated_model_to_gguf(self):
        with TemporaryDirectory() as directory:
            root=Path(directory);(root/'weights.gguf').write_bytes(b'GGUF'+b'fixture-not-model')
            dataset=canonical({'schema':'orez-training-dataset-v1'});(root/'dataset.json').write_bytes(dataset)
            evaluation={'schema':'orez-host-evaluation-v1','status':'COMPLETED','runtime':'HOST_TRANSFORMERS','android_qualification':'PENDING','model':{'manifest_sha256':'a'*64,'files':[]},'dataset_manifest':{'sha256':hashlib.sha256(dataset).hexdigest(),'bytes':len(dataset)}}
            quantization={'schema':'orez-host-quantization-v1','status':'COMPLETED','runtime':'HOST_LLAMA_CPP','android_qualification':'PENDING','base':{'manifest_sha256':'b'*64,'files':[]},'output':sha_file(root/'weights.gguf')}
            (root/'eval.json').write_bytes(canonical(evaluation));(root/'quant.json').write_bytes(canonical(quantization));(root/'license.txt').write_text('Fixture license')
            args=SimpleNamespace(weights=root/'weights.gguf',artifact=[f'DATASET_MANIFEST={root / "dataset.json"}',f'EVALUATION_REPORT={root / "eval.json"}',f'QUANTIZATION_MANIFEST={root / "quant.json"}',f'BASE_LICENSE={root / "license.txt"}',f'OUTPUT_LICENSE={root / "license.txt"}'],output=root/'package')
            with self.assertRaisesRegex(ValueError,'quantization source'):package_model(args)
            self.assertFalse(args.output.exists())


if __name__=='__main__':unittest.main()
