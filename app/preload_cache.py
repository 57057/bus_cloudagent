"""Legacy manual FAQ preloading is retired; answers are admitted automatically."""
import argparse


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.parse_args()
    print("Public answers are cached automatically after frequency and generality checks.")


if __name__ == "__main__":
    main()
