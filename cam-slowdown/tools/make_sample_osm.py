#!/usr/bin/env python3
"""RoadNet 단위 테스트용 작은 OSM XML을 만든다 (app/src/test/resources/sample.osm).
기준점 (37.05, 127.05)에서 동쪽·북쪽 m로 배치. RoadNetTest.kt 의 at(east, north)와 같은 좌표계.

  way 1  간선(trunk, 일방 북행)      east=0,   north 0→4000
  way 2  간선 이어짐(trunk, 일방)    east=0,   north 4000→6000   (4000에서 way 1과 노드 공유)
  way 3  옆 도로(secondary, 양방)    east=50,  north 0→4000      (간선과 안 이어짐 — 동부간선 옆 동일로)
  way 4  교차 도로(secondary, 양방)  (0,4000)→(600,4000)         (90° 옆길)
  way 5  갈림 연결로(link, 일방)     (0,4000)→20° 북동 1000 m
  way 6  반대 차로(trunk, 일방 남행) east=-12, north 4000→0
  way 7  커브(primary, 양방)         (2000,0)에서 북→동으로 반경 500 사분원, 끝 (2500,500)
  way 8  커브 진입(primary, 양방)    (2000,-1000)→(2000,0)
  way 9  왕복 2차선(tertiary, 양방, maxspeed 60)  east=1000, north 0→3000
"""
import math
import os

LAT0, LON0 = 37.05, 127.05
M_LAT = 111_195.0
M_LON = M_LAT * math.cos(math.radians(LAT0))

nodes = {}  # id -> (lat, lon)
ways = []   # (id, [node ids], tags)
_next = [1]


def node(east, north):
    nid = _next[0]
    _next[0] += 1
    nodes[nid] = (LAT0 + north / M_LAT, LON0 + east / M_LON)
    return nid


def way(wid, nids, **tags):
    ways.append((wid, nids, tags))


n_main = [node(0, n) for n in range(0, 4001, 500)]
way(1, n_main, highway="trunk", oneway="yes", maxspeed="80", name="간선도로")
n_main2 = [n_main[-1]] + [node(0, n) for n in range(4500, 6001, 500)]
way(2, n_main2, highway="trunk", oneway="yes", maxspeed="80", name="간선도로")
way(3, [node(50, n) for n in range(0, 4001, 500)], highway="secondary", maxspeed="50", name="옆길")
way(4, [n_main[-1]] + [node(e, 4000) for e in (200, 400, 600)], highway="secondary")
way(5, [n_main[-1]] + [node(1000 * math.sin(math.radians(20)) * k / 2, 4000 + 1000 * math.cos(math.radians(20)) * k / 2) for k in (1, 2)],
    highway="trunk_link", oneway="yes")
way(6, [node(-12, n) for n in range(4000, -1, -500)], highway="trunk", oneway="yes", maxspeed="80", name="간선도로")
curve = [node(2500 + 500 * math.cos(math.radians(th)), 500 * math.sin(math.radians(th))) for th in range(180, 89, -10)]
way(7, curve, highway="primary")
way(8, [node(2000, -1000), node(2000, -500), curve[0]], highway="primary")
way(9, [node(1000, n) for n in range(0, 3001, 500)], highway="tertiary", maxspeed="60", ref="지방도 7")

out = ['<?xml version="1.0" encoding="UTF-8"?>', '<osm version="0.6" generator="make_sample_osm.py">']
for nid, (la, lo) in nodes.items():
    out.append(f'  <node id="{nid}" lat="{la:.7f}" lon="{lo:.7f}" version="1"/>')
for wid, nids, tags in ways:
    out.append(f'  <way id="{wid}" version="1">')
    for nid in nids:
        out.append(f'    <nd ref="{nid}"/>')
    for k, v in tags.items():
        out.append(f'    <tag k="{k}" v="{v}"/>')
    out.append("  </way>")
out.append("</osm>")
path = os.path.join(os.path.dirname(__file__), "..", "app", "src", "test", "resources", "sample.osm")
os.makedirs(os.path.dirname(path), exist_ok=True)
with open(path, "w", encoding="utf-8") as f:
    f.write("\n".join(out) + "\n")
print(path, len(nodes), "nodes", len(ways), "ways")
