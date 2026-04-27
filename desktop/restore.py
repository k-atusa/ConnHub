import os
import base64

def restore(directory):
    if not os.path.exists(directory):
        print(f"error : cannot find {directory}")
        return

    for filename in os.listdir(directory):
        old_path = os.path.join(directory, filename)
        if os.path.isdir(old_path):
            continue
            
        try:
            # restore padding
            pad = len(filename) % 4
            padded_filename = filename
            if pad:
                padded_filename += "=" * (4 - pad)
            
            # Base64URL decode
            decoded_bytes = base64.urlsafe_b64decode(padded_filename)
            original_name = decoded_bytes.decode('utf-8')
            
            new_path = os.path.join(directory, original_name)
            os.rename(old_path, new_path)
            print(f"{filename} -> {original_name}")
        except Exception as e:
            print(f"error : {e} while converting {filename}")

restore("./temp")