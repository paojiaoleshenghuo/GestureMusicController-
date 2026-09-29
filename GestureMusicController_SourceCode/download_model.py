import os
import urllib.request

MODEL_URL = "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"
TARGET_DIR = os.path.join(os.path.dirname(__file__), "app", "src", "main", "assets")
TARGET_FILE = os.path.join(TARGET_DIR, "hand_landmarker.task")

def download_model():
    os.makedirs(TARGET_DIR, exist_ok=True)
    if os.path.exists(TARGET_FILE):
        print(f"Model already exists at: {TARGET_FILE}")
        return

    print(f"Downloading MediaPipe HandLandmarker model to {TARGET_FILE}...")
    urllib.request.urlretrieve(MODEL_URL, TARGET_FILE)
    print(f"Downloaded successfully! File size: {os.path.getsize(TARGET_FILE) / 1024 / 1024:.2f} MB")

if __name__ == "__main__":
    download_model()
