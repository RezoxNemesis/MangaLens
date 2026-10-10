from __future__ import annotations
import argparse
from pathlib import Path
import sys


def bounded(minimum,maximum):
    def parse(value):
        number=int(value)
        if not minimum<=number<=maximum:raise argparse.ArgumentTypeError(f'Use a value between {minimum} and {maximum}')
        return number
    return parse


def parser():
    root=argparse.ArgumentParser(description='Offline Orez dataset/training/evaluation tools. No automatic model downloads or cloud jobs.')
    commands=root.add_subparsers(dest='command',required=True)
    dataset=commands.add_parser('build_dataset');dataset.add_argument('--sources',type=Path,required=True);dataset.add_argument('--output',type=Path,required=True);dataset.add_argument('--seed',type=int,default=42);dataset.add_argument('--held-out-percent',type=bounded(1,50),default=20)
    train=commands.add_parser('train_lora');train.add_argument('--base',type=Path,required=True);train.add_argument('--dataset',type=Path,required=True);train.add_argument('--output',type=Path,required=True);train.add_argument('--device',choices=['cpu','cuda'],default='cpu');train.add_argument('--seed',type=int,default=42);train.add_argument('--max-steps',type=bounded(1,1_000_000),required=True);train.add_argument('--max-length',type=bounded(128,8192),default=1024);train.add_argument('--rank',type=bounded(1,128),default=16);train.add_argument('--batch',type=bounded(1,64),default=1);train.add_argument('--accumulation',type=bounded(1,1024),default=8);train.add_argument('--learning-rate',type=float,default=2e-4);train.add_argument('--target-modules',default='q_proj,k_proj,v_proj,o_proj,gate_proj,up_proj,down_proj');train.add_argument('--gradient-checkpointing',action='store_true')
    merge=commands.add_parser('merge');merge.add_argument('--base',type=Path,required=True);merge.add_argument('--adapter',type=Path,required=True);merge.add_argument('--output',type=Path,required=True)
    evaluate=commands.add_parser('eval');evaluate.add_argument('--base',type=Path,required=True);evaluate.add_argument('--dataset',type=Path,required=True);evaluate.add_argument('--output',type=Path,required=True);evaluate.add_argument('--device',choices=['cpu','cuda'],default='cpu');evaluate.add_argument('--seed',type=int,default=42);evaluate.add_argument('--max-cases',type=bounded(1,5000),default=100);evaluate.add_argument('--max-input-tokens',type=bounded(128,8192),default=4096);evaluate.add_argument('--max-tokens',type=bounded(1,1024),default=288)
    quantize=commands.add_parser('quantize');quantize.add_argument('--base',type=Path,required=True);quantize.add_argument('--converter',type=Path,required=True);quantize.add_argument('--quantizer',type=Path,required=True);quantize.add_argument('--quantization',choices=['Q3_K_M','Q4_K_M','Q5_K_M','Q8_0'],default='Q4_K_M');quantize.add_argument('--output',type=Path,required=True)
    package=commands.add_parser('package_model');package.add_argument('--weights',type=Path,required=True);package.add_argument('--artifact',action='append',required=True,help='ROLE=local-file; dataset manifest and output license are mandatory');package.add_argument('--output',type=Path,required=True)
    return root


def main(argv=None):
    args=parser().parse_args(argv)
    if args.command=='train_lora' and not 0<args.learning_rate<=0.1:
        parser().error('Learning rate must be positive and at most 0.1')
    try:
        if args.output.exists():raise FileExistsError('Choose a new output directory; existing runs are preserved')
        if args.command=='build_dataset':
            from .dataset import build_dataset
            build_dataset(args)
        elif args.command=='package_model':
            from .package import package_model
            package_model(args)
        else:
            from . import model_ops
            {'train_lora':model_ops.train_lora,'merge':model_ops.merge_lora,'eval':model_ops.evaluate,'quantize':model_ops.quantize}[args.command](args)
    except (ValueError,OSError,RuntimeError) as failure:
        print(f'{args.command} failed: {failure}',file=sys.stderr)
        return 1
    return 0
