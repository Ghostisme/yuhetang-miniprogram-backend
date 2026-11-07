"""The combined frontend/Java archive has been replaced by independent releases."""
import argparse

parser = argparse.ArgumentParser(description=__doc__)
parser.parse_args()
parser.error("Build the API with mvn -Papi-release verify and tools/package-1panel.py; build/package the admin in its own repository with npm run build and npm run package:release.")