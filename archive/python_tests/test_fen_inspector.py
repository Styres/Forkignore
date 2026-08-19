import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import cv2
import numpy as np
from tools.validate_all_fen import validate_all

if __name__ == '__main__':
    validate_all()
