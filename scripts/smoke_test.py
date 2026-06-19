#!/usr/bin/env python3
import os
import sys
import time
import hmac
import hashlib
import json
import sqlite3
import subprocess
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

MOCK_PORT = 18089
APP_PORT = 8082
WEBHOOK_SECRET = "test-secret"
DB_PATH = "data/reviewer_smoke.db"
RECEIVED_COMMENT = None
COMMENT_EVENT = threading.Event()

class MockGitHubAnd9RouterHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        # Suppress logging request details to keep test output clean
        pass

    def do_GET(self):
        if "commits/1111/comments" in self.path:
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(b"[]")
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        global RECEIVED_COMMENT
        content_length = int(self.headers.get('Content-Length', 0))
        post_data = self.rfile.read(content_length).decode('utf-8')

        if "access_tokens" in self.path:
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            response = {
                "token": "mock-token",
                "expires_at": "2035-01-01T00:00:00Z"
            }
            self.wfile.write(json.dumps(response).encode('utf-8'))

        elif "chat/completions" in self.path:
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            response = {
                "choices": [{
                    "message": {
                        "content": "Thought: I have enough details.\nFinal Answer: [{\"file\": \"Main.java\", \"line\": 10, \"severity\": \"Error\", \"message\": \"Null pointer risk in E2E smoke test\"}]"
                    },
                    "finish_reason": "stop"
                }]
            }
            self.wfile.write(json.dumps(response).encode('utf-8'))

        elif "commits/1111/comments" in self.path:
            RECEIVED_COMMENT = json.loads(post_data)
            COMMENT_EVENT.set()
            self.send_response(201)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(b'{"id":1001}')
        else:
            self.send_response(404)
            self.end_headers()

def run_mock_server():
    server = HTTPServer(('localhost', MOCK_PORT), MockGitHubAnd9RouterHandler)
    server.serve_forever()

def calculate_signature(payload_bytes, secret):
    hashed = hmac.new(secret.encode('utf-8'), payload_bytes, hashlib.sha256)
    return "sha256=" + hashed.hexdigest()

def wait_for_app_ready(url, timeout=30):
    import urllib.request
    import urllib.error
    start = time.time()
    while time.time() - start < timeout:
        try:
            with urllib.request.urlopen(f"{url}/actuator/health") as response:
                if response.status == 200:
                    return True
        except urllib.error.URLError:
            pass
        time.sleep(0.5)
    return False

def main():
    # 1. Clean up old db
    if os.path.exists(DB_PATH):
        os.remove(DB_PATH)

    # Ensure data directory exists
    os.makedirs(os.path.dirname(DB_PATH), exist_ok=True)

    # 2. Start mock server
    mock_thread = threading.Thread(target=run_mock_server, daemon=True)
    mock_thread.start()
    print(f"Started mock server on port {MOCK_PORT}")

    # 3. Build project to ensure latest changes are used
    print("Building JAR with Gradle...")
    build_res = subprocess.run(["./gradlew", "bootJar"], capture_output=True)
    if build_res.returncode != 0:
        print("Build failed!")
        print(build_res.stderr.decode())
        sys.exit(1)
    print("Build successful.")

    # Find the JAR file
    jar_dir = "build/libs"
    jars = [f for f in os.listdir(jar_dir) if f.endswith(".jar")]
    if not jars:
        print("No JAR found in build/libs!")
        sys.exit(1)
    jar_path = os.path.join(jar_dir, jars[0])
    print(f"Using JAR: {jar_path}")

    # 4. Start Spring Boot App
    print("Starting Spring Boot application...")
    env = os.environ.copy()
    app_process = subprocess.Popen([
        "java", "-jar", jar_path,
        f"--server.port={APP_PORT}",
        f"--app.github.webhook-secret={WEBHOOK_SECRET}",
        f"--app.github.api-url=http://localhost:{MOCK_PORT}",
        f"--app.github.app-id=0",
        f"--app.github.private-key-path=src/test/resources/mock-key.pem", # Use test resource key
        f"--app.nine-router.base-url=http://localhost:{MOCK_PORT}",
        f"--app.nine-router.api-key=dummy-key",
        f"--app.worker.poll-delay-ms=1000",
        f"--spring.datasource.url=jdbc:sqlite:{DB_PATH}"
    ], env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)

    # Thread to read/print spring logs
    def print_logs():
        for line in iter(app_process.stdout.readline, ''):
            print(f"[SPRING] {line.strip()}")
    log_thread = threading.Thread(target=print_logs, daemon=True)
    log_thread.start()

    try:
        app_url = f"http://localhost:{APP_PORT}"
        if not wait_for_app_ready(app_url):
            print("Spring Boot app failed to start within timeout!")
            sys.exit(1)
        print("Spring Boot app is ready.")

        # 5. Send Webhook Request
        payload = {
            "ref": "refs/heads/main",
            "before": "0000",
            "after": "1111",
            "repository": {
                "full_name": "test-owner/test-repo"
            },
            "installation": {
                "id": 0
            }
        }
        payload_bytes = json.dumps(payload).encode('utf-8')
        sig = calculate_signature(payload_bytes, WEBHOOK_SECRET)

        import urllib.request
        import urllib.error
        req = urllib.request.Request(
            f"{app_url}/webhook",
            data=payload_bytes,
            headers={
                "Content-Type": "application/json",
                "X-Hub-Signature-256": sig,
                "X-GitHub-Event": "push",
                "X-GitHub-Delivery": "smoke-delivery-id"
            }
        )

        print("Sending mock webhook event...")
        try:
            with urllib.request.urlopen(req) as resp:
                status_code = resp.status
                body = resp.read().decode()
                print(f"Webhook response status: {status_code}, body: {body}")
                if status_code != 202:
                    print(f"Webhook rejection! Expected 202, got {status_code}")
                    sys.exit(1)
        except urllib.error.HTTPError as he:
            print(f"Webhook request failed: {he.code} - {he.read().decode()}")
            sys.exit(1)

        # 6. Verify SQLite job and GitHub comment posting
        print("Waiting for worker to process job...")
        success = False
        db_conn = None
        for i in range(15):
            time.sleep(1)
            # Check DB
            try:
                db_conn = sqlite3.connect(DB_PATH)
                cursor = db_conn.cursor()
                cursor.execute("SELECT status, attempts, error FROM jobs WHERE delivery_id='smoke-delivery-id'")
                row = cursor.fetchone()
                if row:
                    status, attempts, error = row
                    print(f"DB Job Status: {status}, Attempts: {attempts}, Error: {error}")
                    if status == "COMPLETED":
                        success = True
                        break
                    elif status == "DEAD_LETTER" or status == "FAILED" and attempts >= 3:
                        print("Job failed permanently in DB.")
                        break
            except Exception as e:
                print(f"Error querying SQLite: {e}")
            finally:
                if db_conn:
                    db_conn.close()

        # Check comment event
        comment_posted = COMMENT_EVENT.wait(timeout=2)

        if success and comment_posted and RECEIVED_COMMENT:
            print("\n=================================")
            print("SMOKE TEST SUCCESSFUL!")
            print(f"Comment posted: {RECEIVED_COMMENT['body']}")
            print("=================================\n")
        else:
            print(f"Smoke test failed. Success: {success}, Comment posted: {comment_posted}")
            sys.exit(1)

    finally:
        print("Terminating Spring Boot app...")
        app_process.terminate()
        try:
            app_process.wait(timeout=5)
            print("Spring Boot app terminated successfully.")
        except subprocess.TimeoutExpired:
            app_process.kill()
            print("Spring Boot app killed after timeout.")

        # Clean up db
        if os.path.exists(DB_PATH):
            os.remove(DB_PATH)

if __name__ == "__main__":
    main()
