from .cli import main

if __name__ == "__main__":
    import sys
    raise SystemExit(main(["build_dataset", *sys.argv[1:]]))
