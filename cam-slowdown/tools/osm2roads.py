#!/usr/bin/env python3
"""OpenStreetMap 추출본(.osm.pbf / .osm) → 카메라 감속 앱용 도로망 타일 zip(kr-roads.zip).

앱(RoadNet.kt)이 읽는 형식. 타일은 위도 TILE_LAT × 경도 TILE_LON 크기이고, 경계 밖 margin_km까지의 길을
겹쳐 담아 현재 위치가 든 타일 하나만으로 앞 2 km를 볼 수 있게 한다.

타일 바이너리 (모든 정수는 LEB128 가변길이, 부호 있는 값은 zigzag):
  "KRRT" 2
  bbox: minLat minLon maxLat maxLon (1e7 단위, zigzag)   ← 타일의 확장 경계(카메라 부착 범위)
  nodeCount wayCount
  nodes: (dLat dLon) × nodeCount — 이전 노드와의 차이, zigzag
  nameCount, 이름 × nameCount: 길이(uvar) + UTF-8   ← 0번은 빈 이름
  ways × wayCount: flags(u8) maxspeed(u8) nameIdx(uvar) n, 첫 노드 인덱스(uvar), 이후 이전과의 차이(zigzag)
  junctionCount, 각 교차 노드: 노드 인덱스 차이(uvar) entryCount, 엔트리(way<<11 | 노드위치) 첫값 uvar 이후 차이 zigzag
flags: bit0-3 등급, bit4 일방통행(노드 순서 방향), bit5 교량, bit6 터널.

사용: osm2roads.py south-korea-latest.osm.pbf kr-roads.zip [--major] [--source 설명]
"""
import argparse
import datetime
import math
import sys
import zipfile
from collections import defaultdict

import osmium

TILE_LAT = 0.2
TILE_LON = 0.25
MAX_WAY_NODES = 2000  # 엔트리 packing(11비트) 한계

CLASSES = {
    "motorway": 0, "trunk": 1, "primary": 2, "secondary": 3, "tertiary": 4,
    "unclassified": 5, "residential": 6, "living_street": 7,
    "motorway_link": 8, "trunk_link": 9, "primary_link": 10, "secondary_link": 11, "tertiary_link": 12,
}
MAJOR = {0, 1, 2, 3, 4, 8, 9, 10, 11, 12}


def parse_maxspeed(v):
    if not v:
        return 0
    v = v.strip().lower()
    num = ""
    for ch in v:
        if ch.isdigit():
            num += ch
        elif num:
            break
    if not num:
        return 0
    n = int(num)
    if "mph" in v:
        n = round(n * 1.609)
    return n if 0 < n < 255 else 0


class Collector(osmium.SimpleHandler):
    def __init__(self, major):
        super().__init__()
        self.major = major
        self.ways = []  # (flags, maxspeed, [node_id...], name)
        self.coords = {}  # node_id -> (lat_e7, lon_e7)
        self.skipped = 0

    def way(self, w):
        tags = w.tags
        hw = tags.get("highway")
        cls = CLASSES.get(hw)
        if cls is None:
            return
        if self.major and cls not in MAJOR:
            return
        if tags.get("area") == "yes":
            return
        if tags.get("motor_vehicle") == "no" or tags.get("motorcar") == "no" or tags.get("access") == "no":
            return
        ow = tags.get("oneway", "")
        oneway = ow in ("yes", "true", "1") or tags.get("junction") in ("roundabout", "circular")
        if hw == "motorway" and ow != "no":
            oneway = True
        reverse = ow in ("-1", "reverse")
        if reverse:
            oneway = True
        flags = cls | (0x10 if oneway else 0)
        layer = 0
        try:
            layer = int(tags.get("layer", "0"))
        except ValueError:
            pass
        if tags.get("bridge") not in (None, "no") or layer > 0:
            flags |= 0x20
        if tags.get("tunnel") not in (None, "no") or layer < 0:
            flags |= 0x40
        ms = parse_maxspeed(tags.get("maxspeed"))
        name = (tags.get("name") or tags.get("ref") or "").strip()[:60]
        ids = []
        for n in w.nodes:
            try:
                loc = n.location
                if not loc.valid():
                    continue
            except osmium.InvalidLocationError:
                continue
            self.coords[n.ref] = (int(round(loc.lat * 1e7)), int(round(loc.lon * 1e7)))
            ids.append(n.ref)
        if len(ids) < 2:
            self.skipped += 1
            return
        if reverse:
            ids.reverse()
        # 긴 way는 겹치는 노드로 잘라 연결(교차 노드로 이어진다)
        for i in range(0, len(ids) - 1, MAX_WAY_NODES - 1):
            chunk = ids[i:i + MAX_WAY_NODES]
            if len(chunk) >= 2:
                self.ways.append((flags, ms, chunk, name))


def uvar(out, n):
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return


def svar(out, n):
    uvar(out, (n << 1) ^ (n >> 63) if n < 0 else n << 1)


def encode_tile(ways, coords, bbox):
    """ways: 이 타일에 담을 (flags, maxspeed, [node_id], name) 목록."""
    local = {}
    order = []
    for _, _, ids, _ in ways:
        for nid in ids:
            if nid not in local:
                local[nid] = len(order)
                order.append(nid)
    out = bytearray(b"KRRT\x02")
    for v in bbox:
        svar(out, int(round(v * 1e7)))
    uvar(out, len(order))
    uvar(out, len(ways))
    pl = plo = 0
    for nid in order:
        la, lo = coords[nid]
        svar(out, la - pl)
        svar(out, lo - plo)
        pl, plo = la, lo
    names = {"": 0}
    for _, _, _, name in ways:
        if name not in names:
            names[name] = len(names)
    uvar(out, len(names))
    for name in names:  # 삽입 순서 = 번호 순서
        b = name.encode("utf-8")
        uvar(out, len(b))
        out += b
    use = defaultdict(list)  # local node -> [(way, idx)]
    for wi, (flags, ms, ids, name) in enumerate(ways):
        out.append(flags)
        out.append(ms)
        uvar(out, names[name])
        uvar(out, len(ids))
        prev = 0
        for i, nid in enumerate(ids):
            li = local[nid]
            if i == 0:
                uvar(out, li)
            else:
                svar(out, li - prev)
            prev = li
            use[li].append((wi << 11) | i)
    junctions = sorted(li for li, lst in use.items() if len(lst) >= 2)
    uvar(out, len(junctions))
    prev = 0
    for li in junctions:
        uvar(out, li - prev)
        prev = li
        ent = sorted(use[li])
        uvar(out, len(ent))
        pe = 0
        for i, e in enumerate(ent):
            if i == 0:
                uvar(out, e)
            else:
                svar(out, e - pe)
            pe = e
    return bytes(out), len(order)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("input")
    ap.add_argument("output")
    ap.add_argument("--major", action="store_true", help="tertiary 이상과 연결로만 (파일이 작아진다)")
    ap.add_argument("--margin-km", type=float, default=3.0)
    ap.add_argument("--source", default="")
    args = ap.parse_args()

    col = Collector(args.major)
    col.apply_file(args.input, locations=True, idx="flex_mem")
    print(f"ways {len(col.ways)}, nodes {len(col.coords)}, skipped {col.skipped}", file=sys.stderr)

    m_lat = args.margin_km / 111.0
    m_lon_at = lambda lat: args.margin_km / (111.0 * max(0.2, math.cos(math.radians(lat))))

    tiles = defaultdict(list)
    for way in col.ways:
        lats = [col.coords[n][0] / 1e7 for n in way[2]]
        lons = [col.coords[n][1] / 1e7 for n in way[2]]
        lat0, lat1, lon0, lon1 = min(lats), max(lats), min(lons), max(lons)
        m_lon = m_lon_at((lat0 + lat1) / 2)
        for a in range(math.floor((lat0 - m_lat) / TILE_LAT), math.floor((lat1 + m_lat) / TILE_LAT) + 1):
            for b in range(math.floor((lon0 - m_lon) / TILE_LON), math.floor((lon1 + m_lon) / TILE_LON) + 1):
                tiles[(a, b)].append(way)

    total_nodes = 0
    biggest = (0, "")
    with zipfile.ZipFile(args.output, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for (a, b), ways in sorted(tiles.items()):
            lat_c = (a + 0.5) * TILE_LAT
            m_lon = m_lon_at(lat_c)
            bbox = (a * TILE_LAT - m_lat, b * TILE_LON - m_lon, (a + 1) * TILE_LAT + m_lat, (b + 1) * TILE_LON + m_lon)
            data, n = encode_tile(ways, col.coords, bbox)
            total_nodes += n
            if len(data) > biggest[0]:
                biggest = (len(data), f"t{a}_{b}.bin")
            z.writestr(f"t{a}_{b}.bin", data)
        info = "\n".join([
            f"source={args.source or args.input}",
            f"date={datetime.date.today().isoformat()}",
            f"tiles={len(tiles)}",
            f"ways={len(col.ways)}",
            f"nodes={len(col.coords)}",
            f"tile_lat={TILE_LAT}",
            f"tile_lon={TILE_LON}",
            f"margin_km={args.margin_km}",
            f"classes={'major' if args.major else 'all'}",
            f"format=KRRT2",
        ])
        z.writestr("index.txt", info + "\n")
    print(info, file=sys.stderr)
    print(f"biggest tile {biggest[1]} = {biggest[0] / 1e6:.1f} MB raw, tile nodes total {total_nodes}", file=sys.stderr)


if __name__ == "__main__":
    main()
