from .cli import main

if __name__ == "__main__":
    import sys
    raise SystemExit(main(["train_lora", *sys.argv[1:]]))
