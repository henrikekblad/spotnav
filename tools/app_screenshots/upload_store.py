#!/usr/bin/env python3
"""Upload the store screenshots in fastlane/metadata/android/<locale>/images/phoneScreenshots/ to Google Play.

One Play edit for all locales: each locale's phone screenshots are replaced by the files in its folder (in
name order), and the edit is committed with `changesNotSentForReview`, so the new pictures wait in Play
Console's store listing until they are sent for review there by hand. An app whose changes Play sends for
review automatically refuses that parameter; the edit is then committed without it and goes for review at
once, so send any draft release for review first. Nothing else in the listing changes.

The repository's locale folders are named as fastlane names them; Play's listing names two of them otherwise
(en-US is the listing's British English, nb-NO its Norwegian), as the release workflow maps them too.

    upload_store.py --dry-run                      # check the files against Play's limits, contact nothing
    upload_store.py --key play.json                # upload (the release workflow's service account)
    upload_store.py --key play.json --locales sv-SE en-US
"""

from __future__ import annotations

import argparse
import struct
import sys
from pathlib import Path

PACKAGE = "se.sensnology.spotnav"
ROOT = Path(__file__).resolve().parents[2] / "fastlane" / "metadata" / "android"
PLAY_LOCALE = {"en-US": "en-GB", "nb-NO": "no-NO"}
#: Play's phone screenshot limits: 2 to 8 per locale, each side 320 to 3840 px, the long side at most twice the
#: short one, PNG or JPEG, at most 8 MB.
MIN_COUNT, MAX_COUNT = 2, 8
MIN_SIDE, MAX_SIDE = 320, 3840
MAX_BYTES = 8 * 1024 * 1024


def png_size(path: Path) -> tuple[int, int] | None:
    head = path.read_bytes()[:24]
    if head[:8] != b"\x89PNG\r\n\x1a\n":
        return None
    return struct.unpack(">II", head[16:24])


def shots(locale_dir: Path) -> list[Path]:
    folder = locale_dir / "images" / "phoneScreenshots"
    return sorted(p for p in folder.glob("*") if p.suffix.lower() in (".png", ".jpg", ".jpeg"))


def problems(files: list[Path]) -> list[str]:
    found = []
    if not MIN_COUNT <= len(files) <= MAX_COUNT:
        found.append(f"{len(files)} screenshots; Play takes {MIN_COUNT} to {MAX_COUNT}")
    for path in files:
        if path.stat().st_size > MAX_BYTES:
            found.append(f"{path.name}: larger than 8 MB")
        size = png_size(path) if path.suffix.lower() == ".png" else None
        if size is None:
            continue
        width, height = size
        if not (MIN_SIDE <= min(size) and max(size) <= MAX_SIDE):
            found.append(f"{path.name}: {width}x{height}, each side must be {MIN_SIDE} to {MAX_SIDE} px")
        if max(size) > 2 * min(size):
            found.append(f"{path.name}: {width}x{height}, the long side may be at most twice the short one")
    return found


#: Play's limits for a store listing's texts.
LISTING_LIMITS = {"title": 30, "shortDescription": 80, "fullDescription": 4000}


def listing_texts(locale_dir: Path) -> dict[str, str] | None:
    """A new store listing's texts from a locale folder (title.txt, short_description.txt, full_description.txt), or
    `None` when one is missing, empty or over Play's limit."""
    texts: dict[str, str] = {}
    for key, name in (("title", "title.txt"), ("shortDescription", "short_description.txt"),
                      ("fullDescription", "full_description.txt")):
        path = locale_dir / name
        text = path.read_text(encoding="utf-8").strip() if path.exists() else ""
        if not text or len(text) > LISTING_LIMITS[key]:
            print(f"  {locale_dir.name}/{name}: missing, empty or longer than {LISTING_LIMITS[key]} characters")
            return None
        texts[key] = text
    return texts


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--key", help="the service account's JSON key file")
    parser.add_argument("--locales", nargs="*", help="repository locale folders to upload (default: every one)")
    parser.add_argument("--dry-run", action="store_true", help="check the files only; contact nothing")
    parser.add_argument(
        "--create-listings",
        action="store_true",
        help="add a language the store listing lacks, from its folder's title, short and full description",
    )
    args = parser.parse_args()

    locales = args.locales or sorted(p.name for p in ROOT.iterdir() if p.is_dir())
    plan: dict[str, list[Path]] = {}
    repo_locale: dict[str, str] = {}
    failed = False
    for locale in locales:
        files = shots(ROOT / locale)
        play = PLAY_LOCALE.get(locale, locale)
        print(f"{locale} -> {play}: {', '.join(p.name for p in files) or 'no screenshots'}")
        for problem in problems(files):
            print(f"  {problem}")
            failed = True
        plan[play] = files
        repo_locale[play] = locale
    if failed:
        print("Nothing uploaded: fix the screenshots above first.")
        return 1
    if args.dry_run:
        print("Dry run: nothing uploaded.")
        return 0
    if not args.key:
        parser.error("--key is required unless --dry-run")

    from google.oauth2 import service_account
    from googleapiclient.discovery import build
    from googleapiclient.http import MediaFileUpload

    credentials = service_account.Credentials.from_service_account_file(
        args.key, scopes=["https://www.googleapis.com/auth/androidpublisher"]
    )
    edits = build("androidpublisher", "v3", credentials=credentials, cache_discovery=False).edits()
    edit_id = edits.insert(packageName=PACKAGE, body={}).execute()["id"]
    try:
        # Play takes pictures only for a language the listing has: any other is skipped, and said so.
        listed = {
            listing["language"]
            for listing in edits.listings().list(packageName=PACKAGE, editId=edit_id).execute().get("listings", [])
        }
        for play in sorted(set(plan) - listed):
            texts = listing_texts(ROOT / repo_locale[play]) if args.create_listings else None
            if texts is None:
                print(f"{play}: the store listing has no such language; skipped")
                continue
            edits.listings().update(packageName=PACKAGE, editId=edit_id, language=play, body=texts).execute()
            listed.add(play)
            print(f"{play}: store listing added from {repo_locale[play]}")
        for play, files in plan.items():
            if play not in listed:
                continue
            edits.images().deleteall(
                packageName=PACKAGE, editId=edit_id, language=play, imageType="phoneScreenshots"
            ).execute()
            for path in files:
                mime = "image/png" if path.suffix.lower() == ".png" else "image/jpeg"
                edits.images().upload(
                    packageName=PACKAGE, editId=edit_id, language=play, imageType="phoneScreenshots",
                    media_body=MediaFileUpload(str(path), mimetype=mime),
                ).execute()
            print(f"{play}: {len(files)} screenshots uploaded")
        # Saved in Play Console without being sent for review: that stays a person's step, unless the app's
        # changes are sent for review automatically, where Play refuses the parameter.
        from googleapiclient.errors import HttpError

        try:
            edits.commit(packageName=PACKAGE, editId=edit_id, changesNotSentForReview=True).execute()
            sent = False
        except HttpError as error:
            if error.resp.status != 400 or "changesNotSentForReview must not be set" not in str(error):
                raise
            edits.commit(packageName=PACKAGE, editId=edit_id).execute()
            sent = True
    except Exception:
        edits.delete(packageName=PACKAGE, editId=edit_id).execute()
        raise
    print("Committed and sent for review." if sent else "Saved in Play Console, not sent for review.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
