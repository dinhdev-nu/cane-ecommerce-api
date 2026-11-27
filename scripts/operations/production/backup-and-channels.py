import json
import os
import smtplib
import ssl
import subprocess
import sys
import time
from email.message import EmailMessage
from pathlib import Path

os.umask(0o077)
config = Path('/etc/cane/production')
state = Path('/var/lib/cane-production-backup')
docker = ['/usr/bin/docker', 'exec', '--user', 'postgres', 'cane-production-postgres-1']
pgbackrest = docker + ['pgbackrest', '--stanza=cane']

def env(name):
    return dict(line.split('=', 1) for line in (config / name).read_text().splitlines())

def notify(message_subject, text):
    relay = env('backup-alert.env')
    mail = EmailMessage()
    mail['From'] = relay['EMAIL_FROM']
    mail['To'] = relay['BACKUP_ALERT_TO']
    mail['Reply-To'] = relay['EMAIL_REPLY_TO']
    if feedback := relay.get('EMAIL_FEEDBACK_TO'):
        mail['Return-Path'] = f'<{feedback}>'
    mail['Subject'] = message_subject
    mail.set_content(text)
    with smtplib.SMTP(relay['SMTP_HOST'], int(relay['SMTP_PORT']), timeout=20) as smtp:
        smtp.starttls(context=ssl.create_default_context())
        smtp.login(relay['SMTP_USERNAME'], relay['SMTP_PASSWORD'])
        refused = smtp.send_message(mail, from_addr=relay.get('EMAIL_FEEDBACK_TO') or None)
    if refused:
        raise RuntimeError('SMTP rejected the configured recipient')

def monitor():
    result = subprocess.run(pgbackrest + ['--output=json', 'info'], capture_output=True, check=True, timeout=35)
    stanza = json.loads(result.stdout)[0]
    last_backup = max((entry['timestamp']['stop'] for entry in stanza['backup'] if not entry.get('error')), default=0)
    status = stanza['status']['code']
    wals = subprocess.run(docker + ['find', '/var/lib/postgresql/18/docker/pg_wal/archive_status',
                                  '-name', '*.ready', '-mmin', '+4', '-print'], capture_output=True, check=True, timeout=15)
    return status == 0 and time.time() - last_backup < 26 * 3600 and not wals.stdout

def main(action):
    if action == 'monitor':
        state.mkdir(parents=True, exist_ok=True)
        alert = state / 'backup-unhealthy'
        try:
            healthy = monitor()
        except (OSError, subprocess.SubprocessError, KeyError, ValueError):
            healthy = False
        if healthy:
            alert.unlink(missing_ok=True)
            print('pgBackRest repository, backup age and WAL queue healthy')
        elif not alert.exists() or time.time() - alert.stat().st_mtime > 24 * 3600:
            notify('[CANE] Kiểm tra backup production', 'Kiểm trạng thái kho pgBackRest, bản sao gần nhất và hàng đợi WAL trên cane-production.')
            alert.touch()
        if not healthy:
            raise RuntimeError('Backup or WAL check did not pass')
        return
    if action == 'backup-alert':
        notify('[CANE] Backup production thất bại', 'Kiểm journal của cane-production-backup.service trên VM và xử lý theo runbook.')
        return
    raise SystemExit('Unknown backup command')

if __name__ == '__main__':
    try:
        main(sys.argv[1])
    except Exception as failure:
        raise SystemExit(f'Production backup or notification check failed ({type(failure).__name__})')
