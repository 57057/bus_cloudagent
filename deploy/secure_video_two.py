"""Issue an IP TLS certificate and expose only the Video Two room service on 443."""
import json
from pathlib import Path
import sys
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from deploy.ecs_linux_ssh import connect
from deploy.deploy_ecs_lite import run
from deploy.cloud_assistant import call


def main():
    with connect() as client:
        run(client,'/opt/video-two-tools/certbot/bin/certbot certonly --non-interactive --agree-tos --register-unsafely-without-email --preferred-profile shortlived --webroot --webroot-path /opt/cloudagent/current/site --ip-address 121.43.82.247 --cert-name video-two-ip --config-dir /etc/letsencrypt-video-two --work-dir /var/lib/video-two-acme --logs-dir /var/log/video-two-acme',timeout=160)
        with client.open_sftp() as sftp:
            for name,target in [('Caddyfile','/opt/video-two-tools/Caddyfile'),('reload-proxy.sh','/opt/video-two-tools/reload-proxy.sh'),('video-two-cert-renew.service','/etc/systemd/system/video-two-cert-renew.service'),('video-two-cert-renew.timer','/etc/systemd/system/video-two-cert-renew.timer')]:
                sftp.put(str(ROOT/'deploy/video-two'/name),target)
            sftp.chmod('/opt/video-two-tools/reload-proxy.sh',0o700)
        options='--read-only --memory 64m --cpus 0.25 --cap-drop ALL --cap-add NET_BIND_SERVICE --security-opt no-new-privileges --mount type=bind,src=/opt/video-two-tools/Caddyfile,dst=/etc/caddy/Caddyfile,readonly --mount type=bind,src=/etc/letsencrypt-video-two,dst=/certs,readonly --tmpfs /config:rw,noexec,nosuid,size=4m --tmpfs /data:rw,noexec,nosuid,size=4m'
        run(client,'docker run --rm --network none '+options+' caddy:2.10.2-alpine caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile',timeout=30)
        _,out,err=client.exec_command('docker inspect --format \'{{index .Config.Labels "com.cloudagent.component"}}\' video-two-proxy',timeout=15)
        label=out.read().decode().strip();exists=out.channel.recv_exit_status()==0
        if exists:
            if label!='video-two-proxy':raise RuntimeError('Proxy container name belongs to an unrecognized deployment')
            run(client,'docker restart --timeout 5 video-two-proxy',timeout=25)
        else:
            run(client,'docker run -d --name video-two-proxy --label com.cloudagent.component=video-two-proxy --restart unless-stopped --network host '+options+' caddy:2.10.2-alpine',timeout=25)
        run(client,'systemctl daemon-reload; systemctl enable --now video-two-cert-renew.timer; systemctl is-active video-two-cert-renew.timer',timeout=20)
    target=json.loads((ROOT/'runtime/ecs-target.json').read_text(encoding='utf-8'))
    group=target['SecurityGroupIds']['SecurityGroupId'][0]
    rules=call('DescribeSecurityGroupAttribute',{'SecurityGroupId':group}).get('Permissions',{}).get('Permission',[])
    if not any(p.get('IpProtocol','').lower()=='tcp' and p.get('PortRange')=='443/443' and p.get('SourceCidrIp')=='0.0.0.0/0' for p in rules):
        call('AuthorizeSecurityGroup',{'SecurityGroupId':group,'IpProtocol':'tcp','PortRange':'443/443','SourceCidrIp':'0.0.0.0/0','Policy':'accept','Description':'Video Two encrypted WebSocket room service'})
    path=ROOT/'runtime/video-two/deployment.json';state=json.loads(path.read_text())
    state.update(tls_pending=False,endpoint='wss://121.43.82.247',certificate_issuer='Lets Encrypt',auto_renew_timer='video-two-cert-renew.timer',cloud_resources_purchased=False)
    path.write_text(json.dumps(state,indent=2))
    print('Trusted public WSS configured; two-client protocol and renewal checks remain.')


if __name__=='__main__':main()
