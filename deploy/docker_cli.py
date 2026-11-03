"""Project Docker CLI via the local VM's SSH connection."""
from vm_command import connect
import shlex
import sys

with connect() as client:
    command = shlex.join(["docker", *sys.argv[1:]])
    _, stdout, stderr = client.exec_command(command, timeout=900)
    # Consume both streams together so image pulls cannot block on stderr.
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
