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

places.tsv (zip 안, UTF-8): 이름 \t 위도e7 \t 경도e7 \t 종류 한 글자. 경찰청 카메라 설치장소의 "A→B"·"X방향" 글에서
지명을 좌표로 풀어 단속 방향을 정하는 데 쓴다(CamDirection.kt). 교차로·나들목·역·학교·병원·관공서·교량·터널·동네·아파트 등.
종류: j 교차로/나들목, s 역·터미널, e 학교, h 병원, g 관공서, p 지명(동·리·마을·시), b 교량·터널, a 아파트·단지, o 기타.

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


# 지명 종류 판정. (키, 값 집합 또는 None=아무 값) → 종류 글자. 순서대로 처음 맞는 것.
PLACE_RULES = [
    ("highway", {"motorway_junction"}, "j"),
    ("junction", {"yes"}, "j"),
    ("highway", {"traffic_signals", "crossing", "stop"}, "j"),   # 이름 있는 교차로 노드
    ("railway", {"station", "halt"}, "s"),
    ("public_transport", {"station"}, "s"),
    ("amenity", {"bus_station", "ferry_terminal"}, "s"),
    ("aeroway", {"aerodrome", "terminal"}, "s"),
    ("amenity", {"school", "kindergarten", "college", "university", "childcare"}, "e"),
    ("amenity", {"hospital", "clinic", "doctors", "dentist"}, "h"),
    ("amenity", {"townhall", "community_centre", "police", "fire_station", "post_office", "courthouse", "library", "public_building", "social_facility"}, "g"),
    ("office", {"government", "administrative"}, "g"),
    ("place", None, "p"),
    ("man_made", {"bridge"}, "b"),
    ("natural", {"peak", "hill"}, "p"),
    ("amenity", {"marketplace", "place_of_worship", "bank", "parking", "fuel", "cinema", "theatre"}, "o"),
    ("shop", {"mall", "supermarket", "department_store", "wholesale"}, "o"),
    ("leisure", {"park", "stadium", "sports_centre", "golf_course", "water_park", "pitch"}, "o"),
    ("tourism", {"attraction", "museum", "theme_park", "zoo", "viewpoint", "hotel"}, "o"),
    ("landuse", {"residential", "commercial", "industrial", "retail", "military", "cemetery"}, "a"),
    ("building", {"apartments", "residential", "dormitory", "commercial", "office", "industrial", "public", "civic", "hospital", "school", "university", "retail", "hotel"}, "a"),
    ("historic", None, "o"),
    ("industrial", None, "a"),
    ("military", None, "a"),
]


def place_kind(tags, is_way):
    """(이름, 종류) 또는 None."""
    name = (tags.get("name") or "").strip()
    if is_way:
        # 길 자체의 이름은 지명이 아니다. 교량·터널 구간은 bridge:name/tunnel:name, 없으면 name이 "…교/…터널"일 때만(성수대교, 남산1호터널).
        if tags.get("highway") or tags.get("railway"):
            if tags.get("bridge") not in (None, "no"):
                bn = (tags.get("bridge:name") or "").strip() or (name if name.endswith("교") else "")
                return (bn, "b") if bn and len(bn) <= 40 else None
            if tags.get("tunnel") not in (None, "no"):
                tn = (tags.get("tunnel:name") or "").strip() or (name if name.endswith("터널") else "")
                return (tn, "b") if tn and len(tn) <= 40 else None
            if tags.get("highway") in ("services", "rest_area") and name:
                return (name, "o")
            return None
        if tags.get("waterway"):
            return None
    if not name or len(name) > 40:
        return None
    for key, vals, kind in PLACE_RULES:
        v = tags.get(key)
        if v is not None and (vals is None or v in vals):
            return (name, kind)
    return None


class Collector(osmium.SimpleHandler):
    def __init__(self, major):
        super().__init__()
        self.major = major
        self.ways = []  # (flags, maxspeed, [node_id...], name)
        self.coords = {}  # node_id -> (lat_e7, lon_e7)
        self.skipped = 0
        self.places = {}  # (name, kind, lat_e7//3000, lon_e7//3000) -> (name, lat_e7, lon_e7, kind) — 300 m 격자로 중복 제거

    def add_place(self, name, kind, lat_e7, lon_e7):
        key = (name, kind, lat_e7 // 30000, lon_e7 // 30000)
        if key not in self.places:
            self.places[key] = (name, lat_e7, lon_e7, kind)

    def node(self, n):
        pk = place_kind(n.tags, False)
        if pk is None:
            return
        try:
            loc = n.location
            if not loc.valid():
                return
        except osmium.InvalidLocationError:
            return
        self.add_place(pk[0], pk[1], int(round(loc.lat * 1e7)), int(round(loc.lon * 1e7)))

    def way(self, w):
        tags = w.tags
        pk = place_kind(tags, True)
        if pk is not None:
            lats = []
            lons = []
            for n in w.nodes:
                try:
                    loc = n.location
                    if loc.valid():
                        lats.append(loc.lat)
                        lons.append(loc.lon)
                except osmium.InvalidLocationError:
                    pass
            if lats:
                self.add_place(pk[0], pk[1], int(round(sum(lats) / len(lats) * 1e7)), int(round(sum(lons) / len(lons) * 1e7)))
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
            f"places={len(col.places)}",
        ])
        z.writestr("index.txt", info + "\n")
        places = sorted(col.places.values())
        z.writestr("places.tsv", "".join(f"{name}\t{la}\t{lo}\t{kind}\n" for name, la, lo, kind in places))
        print(f"places {len(places)}", file=sys.stderr)
    print(info, file=sys.stderr)
    print(f"biggest tile {biggest[1]} = {biggest[0] / 1e6:.1f} MB raw, tile nodes total {total_nodes}", file=sys.stderr)


if __name__ == "__main__":
    main()
