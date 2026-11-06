"""Loopback-only browser access to the cloud demo over a host-key-verified SSH tunnel."""
import select
import socketserver
import sys
import json
import paramiko
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from deploy.ecs_linux_ssh import connect


def main():
    with connect() as client:
        transport = client.get_transport()
        transport.set_keepalive(15)
        class Handler(socketserver.BaseRequestHandler):
            def handle(self):
                response_started = False
                try:
                    channel = transport.open_channel('direct-tcpip', ('127.0.0.1', 8080), self.request.getpeername(), timeout=10)
                    if channel is None:
                        return
                    with channel:
                        while True:
                            readable, _, _ = select.select([self.request, channel], [], [], 30)
                            if self.request in readable:
                                data = self.request.recv(65536)
                                if not data: break
                                channel.sendall(data)
                            if channel in readable:
                                data = channel.recv(65536)
                                if not data: break
                                self.request.sendall(data)
                                response_started = True
                except (OSError, EOFError, paramiko.SSHException):
                    if not response_started:
                        body = json.dumps({'message':'云端隧道连接失败，请重新连接后重试'}).encode()
                        try:
                            self.request.sendall(b'HTTP/1.1 502 Bad Gateway\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: '+str(len(body)).encode()+b'\r\n\r\n'+body)
                        except OSError:
                            pass
        class Server(socketserver.ThreadingTCPServer):
            allow_reuse_address = True
            daemon_threads = True
        with Server(('127.0.0.1', 18780), Handler) as server:
            server.timeout = 1
            print('Encrypted cloud portal: http://127.0.0.1:18780/', flush=True)
            while transport.is_active():
                server.handle_request()
            raise ConnectionError('SSH disconnected; restart the cloud tunnel')


if __name__ == '__main__':
    main()
