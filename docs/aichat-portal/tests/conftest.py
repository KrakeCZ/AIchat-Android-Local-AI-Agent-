"""Pytest configuration ensuring analyzer package is importable."""

import sys
from pathlib import Path

# Add docs/aichat-portal directory to sys.path
PORTAL_DIR = Path(__file__).resolve().parent.parent
if str(PORTAL_DIR) not in sys.path:
    sys.path.insert(0, str(PORTAL_DIR))

