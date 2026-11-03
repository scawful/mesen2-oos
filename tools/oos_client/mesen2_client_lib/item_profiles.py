"""Explicit Oracle build-flag mappings for profile-sensitive items."""

from .constants import ITEMS

SENSITIVE_ITEMS = frozenset({"rings", "portalrod", "customrods"})
ITEM_PROFILES = ("default", "legacy")


def item_address(name: str, profile: str | None) -> int:
    if name in SENSITIVE_ITEMS and profile not in ITEM_PROFILES:
        raise ValueError(f"{name} requires --item-profile default or legacy")
    if profile == "legacy":
        if name == "rings":
            return 0x7EF3D8
        if name == "portalrod":
            return 0x7EF351
    return ITEMS[name][0]


def decode_legacy_portalrod(raw: int) -> int:
    if raw not in (0, 1, 2):
        raise ValueError(f"Invalid legacy rod level: {raw}")
    return int(raw == 2)
