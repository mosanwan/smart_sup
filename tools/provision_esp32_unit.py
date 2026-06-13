#!/usr/bin/env python3
import argparse
import json
import os
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path


MAX_UNIT_ID = 999


def repo_root() -> Path:
    return Path(__file__).resolve().parents[1]


def load_registry(path: Path) -> dict:
    if not path.exists():
        return {"current_max_id": -1, "units": []}
    with path.open("r", encoding="utf-8") as f:
        data = json.load(f)
    data.setdefault("current_max_id", -1)
    data.setdefault("units", [])
    return data


def write_registry(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write("\n")


def parse_unit_id(value: str) -> int:
    if not value.isdigit() or len(value) > 3:
        raise argparse.ArgumentTypeError("unit id must be 0-999 or 000-999")
    parsed = int(value, 10)
    if parsed < 0 or parsed > MAX_UNIT_ID:
        raise argparse.ArgumentTypeError("unit id must be in 0-999")
    return parsed


def padded(unit_id: int) -> str:
    return f"{unit_id:03d}"


def allocate_unit(data: dict, requested_id: int | None, note: str) -> int:
    used = {str(item.get("id")) for item in data.get("units", [])}
    if requested_id is None:
        unit_id = int(data.get("current_max_id", -1)) + 1
    else:
        unit_id = requested_id

    if unit_id < 0 or unit_id > MAX_UNIT_ID:
        raise RuntimeError("no ESP32 unit ids left in 000-999")
    if padded(unit_id) in used:
        raise RuntimeError(f"ESP32 unit id {padded(unit_id)} is already allocated")

    data["current_max_id"] = max(int(data.get("current_max_id", -1)), unit_id)
    data.setdefault("units", []).append(
        {
            "id": padded(unit_id),
            "allocated_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
            "status": "allocated",
            "note": note,
        },
    )
    return unit_id


def run_platformio(root: Path, unit_id: int, flash: bool, port: str | None) -> None:
    cmd = ["pio", "run"]
    if flash:
        cmd.extend(["-t", "upload"])
    if port:
        cmd.extend(["--upload-port", port])

    firmware_dir = root / "firmware" / "esp32"
    header_path = firmware_dir / "src" / "factory_unit_id.h"
    env = os.environ.copy()
    print(f"Provisioning ESP32 as SmartSUP-{padded(unit_id)}", flush=True)
    print(" ".join(cmd), flush=True)
    header_path.write_text(
        "#pragma once\n"
        f"#define SMART_SUP_FACTORY_UNIT_ID {unit_id}\n",
        encoding="utf-8",
    )
    try:
        subprocess.run(cmd, cwd=firmware_dir, env=env, check=True)
    finally:
        header_path.unlink(missing_ok=True)
        subprocess.run(["pio", "run", "-t", "clean"], cwd=firmware_dir, env=env, check=False)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Allocate and optionally flash a factory ESP32 SmartSUP unit id.",
    )
    parser.add_argument("--id", type=parse_unit_id, help="explicit id to allocate, e.g. 000 or 7")
    parser.add_argument("--note", default="", help="optional note stored in the registry")
    parser.add_argument(
        "--registry",
        default=str(repo_root() / "config" / "esp32_unit_registry.json"),
        help="registry JSON path",
    )
    parser.add_argument("--build", action="store_true", help="build firmware with the allocated id")
    parser.add_argument("--flash", action="store_true", help="build and upload firmware with the allocated id")
    parser.add_argument("--port", help="serial upload port, e.g. /dev/ttyUSB0")
    args = parser.parse_args()

    if args.port and not args.flash:
        parser.error("--port is only meaningful with --flash")

    root = repo_root()
    registry_path = Path(args.registry)
    if not registry_path.is_absolute():
        registry_path = root / registry_path
    data = load_registry(registry_path)
    unit_id = allocate_unit(data, args.id, args.note)

    print(f"Allocated SmartSUP-{padded(unit_id)}", flush=True)

    if args.build or args.flash:
        run_platformio(root, unit_id, flash=args.flash, port=args.port)
    else:
        print("No firmware build requested. Use --build or --flash for factory provisioning.", flush=True)

    write_registry(registry_path, data)
    print(f"Registry updated: {registry_path}", flush=True)

    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"error: {exc}", file=sys.stderr)
        raise SystemExit(1)
