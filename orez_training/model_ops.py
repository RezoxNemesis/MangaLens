from __future__ import annotations
import hashlib
import json
import os
import random
import subprocess
import time
from pathlib import Path
from .common import canonical, code_receipt, dataset_files, directory_receipt, output_directory, read_manifest, sha_file, write_json


def libraries():
    try:
        import torch
        import transformers
        import peft
    except ImportError as failure:
        raise RuntimeError('Install the optional offline training dependencies for this command') from failure
    return torch, transformers, peft


def local_model(path):
    path = path.resolve(strict=True)
    if not path.is_dir():
        raise ValueError('Use an existing local model directory')
    receipt = directory_receipt(path)
    if not any(item['path'].endswith('.safetensors') for item in receipt['files']):
        raise ValueError('Local safetensors weights are required; remote model code and pickle weights are disabled')
    return path, receipt


def device(torch, name):
    if name == 'cuda' and not torch.cuda.is_available():
        raise ValueError('CUDA was requested but is unavailable')
    return torch.device(name)


def unchanged(path, receipt):
    if directory_receipt(path)!=receipt:raise ValueError('Consumed model directory changed during work')


def verify_dataset(path, manifest_identity):
    if sha_file(path)!=manifest_identity:raise ValueError('Dataset manifest changed during work')
    dataset_files(path)  # Reverify both complete split identities before publishing success.


def train_lora(args):
    torch, transformers, peft = libraries()
    dataset_identity=sha_file(args.dataset);source_code=code_receipt()
    dataset, train, held_out = dataset_files(args.dataset)
    base, base_receipt = local_model(args.base)
    target = device(torch, args.device)
    torch.manual_seed(args.seed); random.seed(args.seed)
    if target.type == 'cuda': torch.cuda.manual_seed_all(args.seed)
    tokenizer = transformers.AutoTokenizer.from_pretrained(str(base), local_files_only=True, trust_remote_code=False)
    if tokenizer.eos_token_id is None: raise ValueError('Base tokenizer must declare EOS')
    if tokenizer.pad_token_id is None: tokenizer.pad_token = tokenizer.eos_token
    model = transformers.AutoModelForCausalLM.from_pretrained(str(base), local_files_only=True, trust_remote_code=False, use_safetensors=True, torch_dtype=torch.float32)
    model.to(target)
    model.config.use_cache = False
    if args.gradient_checkpointing: model.gradient_checkpointing_enable()
    requested = [name.strip() for name in args.target_modules.split(',') if name.strip()]
    if not requested or any(not any(name.endswith('.' + suffix) for name, _ in model.named_modules()) for suffix in requested):
        raise ValueError('LoRA targets do not match this actual base architecture')
    model = peft.get_peft_model(model, peft.LoraConfig(r=args.rank, lora_alpha=args.rank * 2, lora_dropout=0.05, target_modules=requested, task_type='CAUSAL_LM'))
    class Records(torch.utils.data.IterableDataset):
        def __iter__(self):
            with train.open(encoding='utf-8') as stream:
                for line in stream:
                    record=json.loads(line)
                    prefix=tokenizer.apply_chat_template([{'role':'user','content':record['prompt']}], tokenize=False, add_generation_prompt=True)
                    prompt_ids=tokenizer(prefix, add_special_tokens=False)['input_ids']
                    response_ids=tokenizer(record['response'], add_special_tokens=False)['input_ids'] + [tokenizer.eos_token_id]
                    # Reject truncated prompts with no supervised response instead of training empty labels.
                    remaining=args.max_length-len(prompt_ids)
                    if remaining <= 0: continue
                    response_ids=response_ids[:remaining]
                    yield {'input_ids':prompt_ids+response_ids,'attention_mask':[1]*(len(prompt_ids)+len(response_ids)), 'labels':[-100]*len(prompt_ids)+response_ids}
    def collate(rows):
        longest=max(len(row['input_ids']) for row in rows)
        return {name:torch.tensor([row[name]+[tokenizer.pad_token_id if name=='input_ids' else (0 if name=='attention_mask' else -100)]*(longest-len(row[name])) for row in rows],dtype=torch.long) for name in ('input_ids','attention_mask','labels')}
    with output_directory(args.output) as out:
        settings={'seed':args.seed,'max_steps':args.max_steps,'max_length':args.max_length,'rank':args.rank,'targets':requested,'learning_rate':args.learning_rate,'batch':args.batch,'gradient_accumulation':args.accumulation,'device':str(target),'gradient_checkpointing':args.gradient_checkpointing}
        arguments=transformers.TrainingArguments(output_dir=str(out/'trainer'), max_steps=args.max_steps, per_device_train_batch_size=args.batch, gradient_accumulation_steps=args.accumulation, learning_rate=args.learning_rate, seed=args.seed, data_seed=args.seed, save_strategy='no', logging_steps=10, report_to=[], dataloader_num_workers=0, remove_unused_columns=False, use_cpu=target.type=='cpu')
        trainer=transformers.Trainer(model=model,args=arguments,train_dataset=Records(),data_collator=collate)
        began=time.monotonic(); trained=trainer.train()
        adapter=out/'adapter';model.save_pretrained(adapter,safe_serialization=True);tokenizer.save_pretrained(adapter)
        unchanged(base,base_receipt);verify_dataset(args.dataset,dataset_identity)
        if code_receipt()!=source_code:raise ValueError('Training code changed during work')
        write_json(out/'training-manifest.json',{'schema':'orez-host-training-v1','status':'COMPLETED','runtime':'HOST_TRANSFORMERS','android_qualification':'PENDING','base':base_receipt,'dataset_manifest':dataset_identity,'settings':settings,'metrics':trained.metrics,'wall_seconds':time.monotonic()-began,'versions':{'torch':torch.__version__,'transformers':transformers.__version__,'peft':peft.__version__},'device':{'kind':target.type,'name':torch.cuda.get_device_name(target) if target.type=='cuda' else 'CPU'},'code':source_code,'output':directory_receipt(adapter)})


def merge_lora(args):
    torch, transformers, peft = libraries()
    base, base_receipt=local_model(args.base);source_code=code_receipt()
    adapter=args.adapter.resolve(strict=True);adapter_receipt=directory_receipt(adapter)
    if not (adapter/'adapter_model.safetensors').is_file() or not (adapter/'adapter_config.json').is_file():raise ValueError('The supported PEFT adapter_model.safetensors and adapter_config.json are required')
    if any(item['path'].lower().endswith(('.bin','.pt','.pth','.pkl','.pickle')) for item in adapter_receipt['files']):raise ValueError('Pickle adapter fallback artifacts are excluded')
    tokenizer=transformers.AutoTokenizer.from_pretrained(str(base),local_files_only=True,trust_remote_code=False)
    model=transformers.AutoModelForCausalLM.from_pretrained(str(base),local_files_only=True,trust_remote_code=False,use_safetensors=True,torch_dtype=torch.float32)
    model=peft.PeftModel.from_pretrained(model,str(adapter),local_files_only=True).merge_and_unload(safe_merge=True)
    with output_directory(args.output) as out:
        weights=out/'model';model.save_pretrained(weights,safe_serialization=True,max_shard_size='2GB');tokenizer.save_pretrained(weights)
        unchanged(base,base_receipt);unchanged(adapter,adapter_receipt)
        if code_receipt()!=source_code:raise ValueError('Merge code changed during work')
        write_json(out/'merge-manifest.json',{'schema':'orez-host-merge-v1','status':'COMPLETED','runtime':'HOST_TRANSFORMERS','android_qualification':'PENDING','base':base_receipt,'adapter':adapter_receipt,'output':directory_receipt(weights),'code':source_code})


def evaluate(args):
    torch, transformers, _ = libraries()
    dataset_identity=sha_file(args.dataset);source_code=code_receipt()
    dataset, _, held_out=dataset_files(args.dataset)
    base, base_receipt=local_model(args.base);target=device(torch,args.device)
    tokenizer=transformers.AutoTokenizer.from_pretrained(str(base),local_files_only=True,trust_remote_code=False)
    model=transformers.AutoModelForCausalLM.from_pretrained(str(base),local_files_only=True,trust_remote_code=False,use_safetensors=True,torch_dtype=torch.float32).to(target).eval()
    torch.manual_seed(args.seed)
    with output_directory(args.output) as out:
        began=time.monotonic();cases=0;exact=0;errors=0;completion_counts={'EOS':0,'TOKEN_LIMIT':0,'ERROR':0};case_ids=[]
        with held_out.open(encoding='utf-8') as stream,(out/'cases.jsonl').open('xb') as raw:
            for line in stream:
                if cases>=args.max_cases:break
                row=json.loads(line);cases+=1;case_ids.append(row['case_id']);case_started=time.monotonic()
                result={'case_id':row['case_id'],'group_id':row['group_id'],'input_sha256':hashlib.sha256(row['prompt'].encode()).hexdigest(),'reference_sha256':hashlib.sha256(row['response'].encode()).hexdigest()}
                try:
                    prompt=tokenizer.apply_chat_template([{'role':'user','content':row['prompt']}],tokenize=False,add_generation_prompt=True)
                    inputs=tokenizer(prompt,return_tensors='pt',add_special_tokens=False)
                    if inputs['input_ids'].shape[1]>args.max_input_tokens:raise ValueError('INPUT_BUDGET')
                    inputs={key:value.to(target) for key,value in inputs.items()}
                    with torch.inference_mode():generated=model.generate(**inputs,max_new_tokens=args.max_tokens,do_sample=False,pad_token_id=tokenizer.eos_token_id)
                    tokens=generated[0,inputs['input_ids'].shape[1]:];text=tokenizer.decode(tokens,skip_special_tokens=True)
                    terminated=len(tokens)>0 and int(tokens[-1])==tokenizer.eos_token_id
                    completion='EOS' if terminated else 'TOKEN_LIMIT';completion_counts[completion]+=1
                    match=text.strip()==row['response'].strip();exact+=int(match)
                    result.update({'status':'COMPLETED','completion':completion,'output':text,'output_sha256':hashlib.sha256(text.encode()).hexdigest(),'generated_tokens':len(tokens),'exact_match':match})
                except Exception as failure:
                    errors+=1;completion_counts['ERROR']+=1;result.update({'status':'FAILED','error_class':type(failure).__name__})
                result['wall_seconds']=time.monotonic()-case_started;raw.write(canonical(result))
            raw.flush();os.fsync(raw.fileno())
        if cases==0:raise ValueError('Held-out evaluation has no cases')
        unchanged(base,base_receipt);verify_dataset(args.dataset,dataset_identity)
        if code_receipt()!=source_code:raise ValueError('Evaluation code changed during work')
        write_json(out/'evaluation-report.json',{'schema':'orez-host-evaluation-v1','status':'COMPLETED','runtime':'HOST_TRANSFORMERS','android_qualification':'PENDING','quality_judgment':'NOT_REVIEWED','metric_scope':'exact reference comparison only; grammar, multilingual adequacy and safety require rubric review','model':base_receipt,'dataset_manifest':dataset_identity,'held_out':dataset['splits']['held_out'],'selected_case_ids':case_ids,'cases':cases,'errors':errors,'exact_matches':exact,'completion_counts':completion_counts,'wall_seconds':time.monotonic()-began,'settings':{'seed':args.seed,'max_tokens':args.max_tokens,'max_input_tokens':args.max_input_tokens,'max_cases':args.max_cases,'device':str(target)},'raw_cases':sha_file(out/'cases.jsonl'),'code':source_code})


def quantize(args):
    base, receipt=local_model(args.base);source_code=code_receipt()
    converter=args.converter.resolve(strict=True);binary=args.quantizer.resolve(strict=True)
    if converter.suffix!='.py' or not binary.is_file() or not os.access(binary,os.X_OK):raise ValueError('Supply local llama.cpp converter and executable quantizer')
    converter_identity=sha_file(converter);quantizer_identity=sha_file(binary)
    if args.quantization not in {'Q4_K_M','Q5_K_M','Q8_0','Q3_K_M'}:raise ValueError('Unsupported explicit quantization')
    with output_directory(args.output) as out:
        import sys
        unquantized=out/'model-f16.gguf';weights=out/'model.gguf'
        # Explicit argument arrays; no shell, remote fetch, package install or arbitrary flags.
        with (out/'conversion.log').open('xb') as log:
            subprocess.run([sys.executable,str(converter),str(base),'--outfile',str(unquantized),'--outtype','f16'],check=True,stdout=log,stderr=subprocess.STDOUT)
            subprocess.run([str(binary),str(unquantized),str(weights),args.quantization],check=True,stdout=log,stderr=subprocess.STDOUT)
        with weights.open('rb') as stream:
            if stream.read(4)!=b'GGUF':raise ValueError('Quantizer did not produce GGUF')
        output=sha_file(weights);unquantized.unlink()
        unchanged(base,receipt)
        if sha_file(converter)!=converter_identity or sha_file(binary)!=quantizer_identity or code_receipt()!=source_code:raise ValueError('Quantization implementation changed during work')
        write_json(out/'quantization-manifest.json',{'schema':'orez-host-quantization-v1','status':'COMPLETED','runtime':'HOST_LLAMA_CPP','android_qualification':'PENDING','base':receipt,'converter':converter_identity,'quantizer':quantizer_identity,'quantization':args.quantization,'output':output,'code':source_code})
