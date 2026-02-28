import os

def restore_filenames(directory):
    if not os.path.exists(directory):
        print(f"error : cannot fine {directory}")
        return

    for filename in os.listdir(directory):
        old_path = os.path.join(directory, filename)
        if os.path.isdir(old_path):
            continue
            
        try:
            decoded_bytes = bytes.fromhex(filename)
            original_name = decoded_bytes.decode('utf-8')
            new_path = os.path.join(directory, original_name)
            os.rename(old_path, new_path)
            print(f"{filename} -> {original_name}")
        except Exception as e:
            print(f"error : {e} while converting {filename}")

target_dir = "./temp"
restore_filenames(target_dir)
