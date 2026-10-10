from .cli import main

if __name__ == "__main__":
    import sys
    raise SystemExit(main(["package_model", *sys.argv[1:]]))
