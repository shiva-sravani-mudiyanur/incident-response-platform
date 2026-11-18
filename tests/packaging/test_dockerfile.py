"""Offline packaging contracts; no Docker daemon, Maven, or third-party packages.

Run separately from application tests:
    python3 -m unittest discover -s tests/packaging -p test_dockerfile.py -v
Image assembly skips tests; release verification must run the Maven suite separately.
"""
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import shutil
import subprocess
import threading
import time
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
MODULES = tuple(node.text for node in ET.parse(ROOT / "pom.xml").findall("m:modules/m:module", NS))


class DockerfileContractTest(unittest.TestCase):
    def test_selected_reactor_build_and_minimal_runtime(self):
        path = ROOT / "Dockerfile"
        self.assertTrue(path.is_file(), "root reusable Dockerfile is missing")
        text = path.read_text()
        instructions = re.sub(r"\\\n\s*", " ", text)
        self.assertEqual(len(MODULES), 6)
        self.assertRegex(text, r"FROM maven:[^\s]*eclipse-temurin-21[^\s]* AS build")
        self.assertRegex(text, r"FROM eclipse-temurin:21-jre-[^\s]+ AS runtime")
        self.assertEqual(re.findall(r"^ARG (\w+)", text, re.M), ["MODULE"])
        for module in MODULES:
            self.assertIn(f"COPY {module}/pom.xml {module}/pom.xml", text)
            self.assertIn(module, text.split("esac", 1)[0])
        self.assertIn('COPY ${MODULE}/src/main/ ${MODULE}/src/main/', text)
        self.assertNotRegex(text, r"COPY\s+\.\s")
        self.assertIn('-pl "$MODULE" -am -Dmaven.test.skip=true package', instructions)
        self.assertIn("dependency:go-offline", text)
        self.assertLess(text.index("dependency:go-offline"), text.index("COPY ${MODULE}/src/main/"))
        self.assertGreaterEqual(text.count("target=/root/.m2,sharing=locked"), 2)
        runtime = text.split(" AS runtime", 1)[1]
        self.assertIn("curl", runtime)
        self.assertIn("--no-install-recommends", runtime)
        self.assertIn("rm -rf /var/lib/apt/lists/*", runtime)
        self.assertRegex(runtime, r"(?m)^USER 10001:10001$")
        self.assertIn("/tmp", runtime)
        self.assertIn("--chmod=0555", runtime)
        self.assertIn("sed -i 's/\\r$//' /app/healthcheck.sh", runtime)
        entrypoint = re.search(r"(?m)^ENTRYPOINT (.+)$", runtime)
        self.assertIsNotNone(entrypoint)
        assert entrypoint is not None
        self.assertEqual(json.loads(entrypoint.group(1)), ["java", "-Djava.io.tmpdir=/tmp", "-jar", "/app/app.jar"])
        self.assertNotIn("maven", runtime.lower())
        self.assertNotIn("src/main", runtime)
        self.assertNotRegex(instructions, r"(?i)(?:ARG|ENV)\s+\S*(?:password|secret|token|api_key)")
        self.assertNotIn("COPY .env", text)
        self.assertIn("assembly only", text)


class DockerignoreContractTest(unittest.TestCase):
    def test_context_is_allowlisted_without_local_outputs_or_credentials(self):
        path = ROOT / ".dockerignore"
        self.assertTrue(path.is_file(), "allowlisted Docker context is missing")
        rules = [line.strip() for line in path.read_text().splitlines()
                 if line.strip() and not line.startswith("#")]
        self.assertEqual(rules[0], "**")
        required = {"!pom.xml", "!docker/", "!docker/healthcheck.sh"}
        for module in MODULES:
            required.update({f"!{module}/", f"!{module}/pom.xml",
                             f"!{module}/src/", f"!{module}/src/main/", f"!{module}/src/main/**"})
        self.assertEqual({rule for rule in rules if rule.startswith("!")}, required)
        # Defense in depth even if credentials are accidentally placed in src/main.
        for pattern in ("**/.env", "**/.env.*", "**/*.pem", "**/*.key", "**/*.p12",
                        "**/*.pfx", "**/*.jks", "**/*.keystore", "**/target", "**/.git"):
            self.assertIn(pattern, rules)
            self.assertGreater(rules.index(pattern), max(i for i, rule in enumerate(rules) if rule.startswith("!")))


class HealthcheckContractTest(unittest.TestCase):
    def test_shell_helper_is_lf_and_uses_container_port(self):
        helper = ROOT / "docker/healthcheck.sh"
        self.assertTrue(helper.is_file(), "bounded curl health helper is missing")
        self.assertNotIn(b"\r", helper.read_bytes())
        self.assertTrue(helper.read_bytes().startswith(b"#!/bin/sh\n"))
        # Windows Git checkouts need not preserve a host executable bit;
        # Docker COPY --chmod guarantees it inside the runtime image.
        shell = shutil.which("sh")
        self.assertIsNotNone(shell, "behavior contract requires a POSIX shell")
        assert shell is not None
        self.assertIsNotNone(shutil.which("curl"), "behavior contract requires real curl")
        subprocess.run(["sh", "-n", str(helper)], check=True)

        class HealthHandler(BaseHTTPRequestHandler):
            status = 200
            delay = 0
            requests = []

            def do_GET(self):
                self.requests.append(self.path)
                time.sleep(self.delay)
                self.send_response(self.status)
                self.end_headers()

            def log_message(self, format, *args):
                pass

        # Ephemeral loopback only; never touches native application ports.
        server = ThreadingHTTPServer(("127.0.0.1", 0), HealthHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        env = dict(os.environ, SERVER_PORT=str(server.server_port),
                   http_proxy="http://127.0.0.1:1", HTTP_PROXY="http://127.0.0.1:1",
                   ALL_PROXY="http://127.0.0.1:1", no_proxy="", NO_PROXY="")
        try:
            for status in (200, 503, 404, 302):
                with self.subTest(status=status):
                    HealthHandler.status = status
                    result = subprocess.run([shell, str(helper)], env=env, capture_output=True, timeout=8)
                    self.assertEqual(result.returncode == 0, status == 200, result.stderr)
                    self.assertEqual(HealthHandler.requests[-1], "/actuator/health")
            HealthHandler.status = 200
            HealthHandler.delay = 6
            start = time.monotonic()
            result = subprocess.run([shell, str(helper)], env=env, capture_output=True, timeout=8)
            self.assertNotEqual(result.returncode, 0)
            self.assertLess(time.monotonic() - start, 7, "curl must have a bounded total timeout")
            for port in (None, "", "0", "65536", "abc", "8080/path", "-1"):
                with self.subTest(port=port):
                    invalid_env = dict(env)
                    if port is None:
                        invalid_env.pop("SERVER_PORT")
                    else:
                        invalid_env["SERVER_PORT"] = port
                    result = subprocess.run([shell, str(helper)], env=invalid_env, capture_output=True, timeout=2)
                    self.assertNotEqual(result.returncode, 0)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)


if __name__ == "__main__":
    unittest.main()
