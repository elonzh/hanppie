"""Known firmware fingerprints used only to classify a diagnosis snapshot."""

STOCK_HASHES = {
    "/system/etc/dji.json": "63db3ea0b9931cc519255265be66af35c93623c636a91ed59c3ff10280fb8ed8",
    "/system/bin/dji_hdvt_uav": "19d957e93672ce105d09eec509ec2fb873c8eb69a9287e0a505a6438538b2b38",
}

SDK_PATCH_HASHES = {
    "/system/etc/dji.json": "19db1dfcab6d21840735a69210d5c4dd0083759c2b02fbd708fd0f9892d98307",
    "/system/bin/dji_hdvt_uav": "da1ccac46c2c56a70af21f6b0da747e84040ba2ea850a29e07af27bc3c21c90f",
}


def classify_runtime(hashes: dict[str, str]) -> str:
    if hashes == STOCK_HASHES:
        return "stock"
    if hashes == SDK_PATCH_HASHES:
        return "volatile-sdk-patch"
    return "unknown"
