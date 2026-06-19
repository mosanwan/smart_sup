import os

Import("env")

DEFAULT_VERSIONS = {
    "lolin32_lite": "0.2.15",
    "imu_i2c_scan": "imu-i2c-scan",
    "imu_yb_probe": "imu-yb-probe",
}

pio_env = env.get("PIOENV", "")
version = os.environ.get("SMART_SUP_VERSION") or DEFAULT_VERSIONS.get(pio_env, "dev")
env.Append(CPPDEFINES=[("SMART_SUP_VERSION", f'\\"{version}\\"')])
