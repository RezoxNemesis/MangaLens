from .cli import main

if __name__ == "__main__":
    import sys
    raise SystemExit(main(["eval", *sys.argv[1:]]))
