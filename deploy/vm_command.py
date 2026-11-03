"""Run a command on this project's local Docker VM over key-authenticated SSH."""
from pathlib import Path
import argparse
import paramiko
import sys

ROOT = Path(__file__).resolve().parents[1]
VM_DIR = ROOT / "runtime/docker-vm"

def connect():
    client = paramiko.SSHClient()
    known_hosts = VM_DIR / "known_hosts"
    if known_hosts.exists():
        client.load_host_keys(str(known_hosts))
        client.set_missing_host_key_policy(paramiko.RejectPolicy())
    else:
        client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect("127.0.0.1", port=2222, username="cloudagent",
                   key_filename=str(VM_DIR / "id_rsa"),
                   timeout=10, auth_timeout=10, banner_timeout=10,
                   allow_agent=False, look_for_keys=False)
    if not known_hosts.exists():
        client.save_host_keys(str(known_hosts))
    return client

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command")
    parser.add_argument("--upload", nargs=2, metavar=("LOCAL", "REMOTE"))
    args = parser.parse_args()
    with connect() as client:
        if args.upload:
            with client.open_sftp() as sftp:
                sftp.put(*args.upload)
        _, stdout, stderr = client.exec_command(args.command, timeout=900)
        channel = stdout.channel
        while not channel.exit_status_ready() or channel.recv_ready() or channel.recv_stderr_ready():
            if channel.recv_ready():
                sys.stdout.buffer.write(channel.recv(65536))
                sys.stdout.buffer.flush()
            if channel.recv_stderr_ready():
                sys.stderr.buffer.write(channel.recv_stderr(65536))
                sys.stderr.buffer.flush()
            if not channel.recv_ready() and not channel.recv_stderr_ready():
                import time
                time.sleep(0.1)
        sys.exit(channel.recv_exit_status())

if __name__ == "__main__":
    main()
