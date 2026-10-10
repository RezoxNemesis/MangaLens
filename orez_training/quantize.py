from .cli import main

if __name__ == "__main__":
    import sys
    raise SystemExit(main(["quantize", *sys.argv[1:]]))
