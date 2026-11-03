"""Create a project-owned Ubuntu VM for Docker on Windows without WSL."""
from pathlib import Path
import io
import os
import subprocess
import paramiko
import pycdlib

ROOT = Path(__file__).resolve().parents[1]
VM_DIR = ROOT / "runtime/docker-vm"
VBOX = Path("C:/Program Files/Oracle/VirtualBox/VBoxManage.exe")
NAME = "CloudAgent-Docker"

def vbox(*args):
    subprocess.run([str(VBOX), *map(str, args)], check=True)

def main():
    VM_DIR.mkdir(parents=True, exist_ok=True)
    key_path = VM_DIR / "id_rsa"
    if not key_path.exists():
        paramiko.RSAKey.generate(3072).write_private_key_file(str(key_path))
    key = paramiko.RSAKey.from_private_key_file(str(key_path))
    public_key = f"{key.get_name()} {key.get_base64()} cloudagent-docker"
    user_data = f"""#cloud-config
hostname: cloudagent-docker
manage_etc_hosts: true
ssh_pwauth: false
disable_root: true
users:
  - name: cloudagent
    shell: /bin/bash
    sudo: ALL=(ALL) NOPASSWD:ALL
    groups: [sudo]
    lock_passwd: true
    ssh_authorized_keys:
      - {public_key}
package_update: true
packages:
  - docker.io
  - docker-compose-v2
  - curl
runcmd:
  - [systemctl, enable, --now, docker]
  - [usermod, -aG, docker, cloudagent]
  - [mkdir, -p, /home/cloudagent/milvus]
  - [chown, -R, 'cloudagent:cloudagent', /home/cloudagent/milvus]
  - [sh, -c, 'docker version > /var/log/cloudagent-docker-ready.log 2>&1']
"""
    metadata = "instance-id: cloudagent-docker-20261012\nlocal-hostname: cloudagent-docker\n"
    seed = VM_DIR / "seed.iso"
    if not seed.exists():
        iso = pycdlib.PyCdlib()
        iso.new(interchange_level=3, joliet=3, vol_ident="cidata")
        for name, contents, iso_name in [("user-data", user_data, "USERDATA.;1"),
                                         ("meta-data", metadata, "METADATA.;1")]:
            data = contents.encode()
            iso.add_fp(io.BytesIO(data), len(data), iso_path="/" + iso_name, joliet_path="/" + name)
        iso.write(str(seed))
        iso.close()
    disk = VM_DIR / "ubuntu.vdi"
    if not disk.exists():
        raise RuntimeError("Convert runtime/downloads/ubuntu.img to runtime/docker-vm/ubuntu.vdi first")
    existing = subprocess.run([str(VBOX), "list", "vms"], capture_output=True, text=True, check=True).stdout
    if f'"{NAME}"' not in existing:
        vbox("createvm", "--name", NAME, "--ostype", "Ubuntu_64", "--basefolder", VM_DIR, "--register")
        vbox("modifyvm", NAME, "--memory", "4096", "--cpus", "4", "--firmware", "efi",
             "--graphicscontroller", "vmsvga", "--vram", "16", "--nic1", "nat",
             "--nictype1", "virtio", "--audio-enabled", "off", "--usb", "off")
        vbox("modifyvm", NAME, "--nat-localhostreachable1", "on")
        for name, host, guest in [("ssh", 2222, 22), ("milvus", 19530, 19530), ("health", 9091, 9091)]:
            vbox("modifyvm", NAME, "--natpf1", f"{name},tcp,127.0.0.1,{host},,{guest}")
        vbox("storagectl", NAME, "--name", "SATA", "--add", "sata", "--controller", "IntelAhci")
        vbox("storagectl", NAME, "--name", "SCSI", "--add", "virtio-scsi", "--controller", "VirtIO")
        vbox("storageattach", NAME, "--storagectl", "SCSI", "--port", "0", "--device", "0",
             "--type", "hdd", "--medium", disk)
        vbox("storageattach", NAME, "--storagectl", "SCSI", "--port", "1", "--device", "0",
             "--type", "dvddrive", "--medium", seed)
        vbox("modifyvm", NAME, "--uart1", "0x3f8", "4", "--uartmode1", "file", VM_DIR / "serial.log")
    info = subprocess.run([str(VBOX), "showvminfo", NAME, "--machinereadable"],
                          capture_output=True, text=True, check=True).stdout
    if 'VMState="running"' not in info:
        vbox("startvm", NAME, "--type", "headless")
    print("Docker VM started; SSH on localhost:2222, Milvus on localhost:19530.")

if __name__ == "__main__":
    main()
