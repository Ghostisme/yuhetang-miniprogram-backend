#!/usr/bin/env python3
"""Prepare the reviewed November 2026 client menus; source files stay unchanged."""
import argparse
import copy
import hashlib
import json
from pathlib import Path

from PIL import Image, ImageOps

ROOT = Path(__file__).resolve().parents[1]
# Read against each of the nine Chinese menu images. PDF-only additions remain off.
DEFINITIONS = [
    ("yuht-symptom-care", "对症调理", "Symptom-Specific Care", "特色调理", "60分钟", "massage", []),
    ("massage", "中医推拿", "TCM Massage", "推拿养护", "60分钟", "massage", ["经络舒缓"]),
    ("moxa", "扶阳悬灸", "Moxibustion", "艾灸温养", "60分钟", "moxa", ["蕲艾温养"]),
    ("yuht-spa", "高端精油SPA", "Essential Oil SPA", "精油养护", "60分钟", "ritual", []),
    ("foot", "香薰足疗", "Foot Massage", "足部养护", "60分钟", "foot", ["足下轻盈"]),
    ("yuht-specific-care", "专项调理", "Specific Conditioning", "特色调理", "60分钟", "massage", []),
    ("yuht-facial-care", "容颜调理", "Facial Care & Therapy", "容颜养护", "60分钟", "ritual", ["容颜内调", "面部容颜"]),
    ("yuht-cupping", "拔罐", "Cupping", "传统养护", "每项", "guasha", []),
    ("guasha", "刮痧", "Skin Scraping", "传统养护", "每项", "guasha", ["东方刮痧"]),
    ("yuht-pedicure", "扦／刮／捏脚", "Pedicure", "足部养护", "每项", "foot", []),
    ("yuht-ear-picking", "古法采耳", "Ear Picking", "采耳养护", "30分钟", "ritual", []),
    ("yuht-visceral-care", "脏腑调理", "Visceral Conditioning", "特色调理", "60分钟", "massage", []),
    ("yuht-meridian-care", "经络疏通", "Meridian Dredging", "特色调理", "60分钟", "massage", []),
    ("yuht-ear-head", "采洗耳头部按摩", "Ear Cleaning & Head Massage", "采耳养护", "60分钟", "ritual", []),
    ("yuht-premium-foot", "大桶火龙", "Premium Foot Massage", "足部养护", "80分钟", "foot", []),
    ("yuht-pediatric-tuina", "小儿推拿", "Pediatric Tuina", "推拿养护", "时长待门店确认", "massage", []),
    ("yuht-package-a", "A套餐·精油SPA＋扶阳悬灸", "Package A", "组合套餐", "120分钟", "moxa", []),
    ("yuht-package-b", "B套餐·中医推拿＋扶阳悬灸", "Package B", "组合套餐", "120分钟", "massage", []),
    ("yuht-package-c", "C套餐·香薰足疗＋精油SPA", "Package C", "组合套餐", "120分钟", "foot", []),
    ("yuht-package-d", "D套餐·香薰足疗＋中医推拿", "Package D", "组合套餐", "120分钟", "foot", []),
]
KEYS = {"symptom": "yuht-symptom-care", "massage": "massage", "moxa": "moxa", "spa": "yuht-spa", "foot": "foot",
        "specific": "yuht-specific-care", "facial": "yuht-facial-care", "cupping": "yuht-cupping", "guasha": "guasha",
        "pedicure": "yuht-pedicure", "ear": "yuht-ear-picking", "visceral": "yuht-visceral-care",
        "meridian": "yuht-meridian-care", "ear-head": "yuht-ear-head", "premium-foot": "yuht-premium-foot",
        "pediatric": "yuht-pediatric-tuina", **{k: "yuht-package-" + k for k in "abcd"}}
LOW = dict(symptom=1088, massage=[228,268,368], moxa=[348,388,498], spa=[398,458,558], foot=[228,268,368],
           specific=[338,398,498], cupping=[98,128,158], guasha=[98,128,158], pedicure=128, ear=188)
MID = dict(symptom=1088, massage=[228,288,388], moxa=[348,398,520], spa=[388,488,588], foot=[228,288,388],
           specific=[398,458,498], cupping=[98,128,198], guasha=[98,128,198], pedicure=[98,128], ear=188)
HIGH = dict(symptom=1088, massage=[268,378,498], moxa=[388,498,618], spa=[458,568,688], foot=[268,378,498],
            visceral=[368,478,598], cupping=[128,188,198], guasha=[128,188,198], pedicure=128, ear=188)
PACKAGES = {
    "low": dict(a=[716,816,1026], b=[546,626,836], c=[596,696,896], d=[426,506,706]),
    "mid": dict(a=[706,856,1078], b=[546,656,878], c=[586,746,946], d=[426,546,746]),
    "high": dict(a=[816,1036,1276], b=[626,846,1086], c=[696,916,1156], d=[506,726,966]),
}
MENUS = [
    ("bai-le-men", "百乐门店", "Weixin Image_20261105165734_717_402.jpg", {**MID, "facial":[298,298,398]}, "mid"),
    ("shanghai", "上海中心店", "Weixin Image_20261105165736_719_402.jpg", {**HIGH, "ear-head":288, "facial":498}, "high"),
    ("wujiang", "吴江路店", "Weixin Image_20261105165737_720_402.jpg", LOW, "low"),
    ("huashan", "华山路店", "Weixin Image_20261105165740_721_402.jpg", {**MID, "pedicure":128}, "mid"),
    ("taikoo", "太古汇店", "Weixin Image_20261105165743_722_402.jpg", LOW, "low"),
    ("meihua", "梅花路店", "Weixin Image_20261105165745_723_402.jpg",
     {k:v for k,v in {**HIGH, "meridian":[458,568,688]}.items() if k not in {"spa","foot","pedicure","ear"}}, "high"),
    ("sichuan", "四川北路店", "Weixin Image_20261105165746_724_402.jpg", LOW, "low"),
    ("changde", "常德路店", "Weixin Image_20261105165747_725_402.jpg", {**MID, "facial":[298,298,398], "premium-foot":[348,388,448]}, "mid"),
    ("yuyao", "余姚路店", "Weixin Image_20261105165749_726_402.jpg",
     {k:v for k,v in {**LOW, "pedicure":[98,128,158], "premium-foot":[328,368,428]}.items() if k != "ear"}, "low"),
]


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def price(value, unit="次", basis="menu-image"):
    values = dict(zip(["高级","特级","专家"], value)) if isinstance(value, list) else {"统一":value}
    return {"currency":"CNY", "unit":unit, "values":values, "basis":basis,
            "note":"专家价格未标注，请向门店确认" if isinstance(value,list) and len(value)==2 else ""}


def prepare(source, target):
    target.mkdir(parents=True, exist_ok=True)
    (target / "menus").mkdir(exist_ok=True)
    services=[]
    for i,(sid,name,en,category,duration,image,aliases) in enumerate(DEFINITIONS):
        services.append(dict(id=sid,name=name,english=en,category=category,duration=duration,image=f"/media/{image}.webp",
                             subtitle=f"{category} · {duration}",tag="组合套餐" if category=="组合套餐" else "门店品项",
                             description=f"客户服务价目资料列示项目。具体服务内容和价格请查看所选门店介绍。",
                             steps=[],sortOrder=(i+1)*10,enabled=sid!="yuht-pediatric-tuina",aliases=aliases))
    by_id={s["id"]:s for s in services}
    stores=[]
    for sid,name,filename,raw,group in MENUS:
        original=source/filename
        raw=copy.deepcopy(raw)
        raw.update(PACKAGES[group])
        if sid=="meihua":
            for key in ("c","d"): raw.pop(key)
        image=ImageOps.exif_transpose(Image.open(original)).convert("RGB")
        image.thumbnail((2000,3000),Image.Resampling.LANCZOS)
        dest=target/"menus"/(sid+".jpg")
        image.save(dest,"JPEG",quality=90,optimize=True)
        content=dest.read_bytes()
        menu=dict(file="menus/"+dest.name,sha256=hashlib.sha256(content).hexdigest(),bytes=len(content),
                  width=image.width,height=image.height,sourceFilename=filename,sourceSha256=hashlib.sha256(original.read_bytes()).hexdigest())
        items=[]
        for key,value in raw.items():
            item_id=KEYS[key]
            item=dict(serviceId=item_id, name=by_id[item_id]["name"], duration=by_id[item_id]["duration"],
                      price=price(value,"套餐" if key in "abcd" else "项" if key in {"cupping","guasha","pedicure"} else "次"),
                      enabled=True,reviewStatus="MENU_VERIFIED",source="menu-image")
            if key=="facial":item["name"]="面部容颜" if sid=="shanghai" else "容颜内调"
            if sid=="meihua" and key=="a":item["name"]="A套餐·经络疏通＋扶阳悬灸"
            if key in "abcd":
                components={"a":["meridian" if sid=="meihua" else "spa","moxa"],"b":["massage","moxa"],"c":["foot","spa"],"d":["foot","massage"]}[key]
                item["components"]=[dict(serviceId=KEYS[k],minutes=60) for k in components]
            items.append(item)
        # PDF lists these services but the corresponding Chinese menu does not.
        # Keep the records for review without silently opening them for bookings.
        if sid in {"shanghai","taikoo","huashan","wujiang","meihua"}:
            extra=dict(serviceId=KEYS["pediatric"],name="小儿推拿",duration="30分钟" if sid=="meihua" else "30–60分钟",
                       price={"currency":"CNY","unit":"次","values":{},"basis":"pdf-only","note":"本店分档及适用时长待确认"},
                       enabled=False,reviewStatus="NEEDS_CONFIRMATION",source="pdf-page-1")
            if sid=="meihua":extra["price"]=price(168,"30分钟","pdf-only")
            if sid=="taikoo":extra["price"]=price(268,"小时","pdf-only")
            if sid=="huashan":extra["price"]=price(288,"小时","pdf-only")
            if sid=="wujiang":extra["price"]["note"]="PDF 列示268–368元/小时；分档对应关系待确认"
            items.append(extra)
        if sid in {"wujiang","sichuan","huashan"}:
            items.append(dict(serviceId=KEYS["facial"],name="容颜调理",duration="60分钟",
                              price={"currency":"CNY","unit":"次","values":{},"basis":"pdf-only","note":"PDF 列示298–398元；本店分档待确认"},
                              enabled=False,reviewStatus="NEEDS_CONFIRMATION",source="pdf-page-1"))
        if sid=="yuyao":
            items.append(dict(serviceId=KEYS["ear"],name="古法采耳",duration="30分钟",price=price(188,"次","pdf-only"),
                              enabled=False,reviewStatus="NEEDS_CONFIRMATION",source="pdf-page-1"))
        stores.append(dict(id=sid,name=name,aliases=["梅花路中医诊所"] if sid=="meihua" else [],menu=menu,items=items))
    pdf=source/"Yu Health - Services and Price Guide.pdf"
    manifest=dict(schemaVersion=1,datasetId="client-services-20261108",sourcePolicy="branch-menu-images-first; pdf-only-items-disabled",
                  sourcePdf=dict(filename=pdf.name,sha256=hashlib.sha256(pdf.read_bytes()).hexdigest(),statedValidAsOf="2026-09"),
                  notes=["价格按各店中文价目图片逐项核对；套餐采用红色活动价，不导入划线原价。",
                         "中文图未列出、仅PDF提及的9条门店项目默认不上架，待品牌确认。",
                         "高级/特级/专家为项目价格档位，不改变现有调理师等级。",
                         "每项计费项目不推算服务分钟数。拔罐与刮痧分为两个可选品项，均保留每项价格；扦/刮/捏脚保留组合名称并注明每项计费。",
                         "项目封面复用当前已授权素材；9张价目图作为独立素材归档，不裁成项目写真。",
                         "每个门店独立建档，拥有各自的品项ID、价格、时长和上下架状态；品项定义用于整理资料，不写入总部品牌品项。",
                         "本包不更新门店地址、营业时间、会员折扣、预约规则或账号。"],services=services,stores=stores)
    write_json(target/"services.json",manifest)
    lines=["# 客户品项资料核对表", "", "价格单位为人民币；高级／特级／专家依次列示。只录入套餐红色活动价。", "", "## 核对规则", ""]
    lines += ["- "+n for n in manifest["notes"]]
    for store in stores:
        lines += ["", "## "+store["name"], "", "| 项目 | 时长/单位 | 价格 | 初始化状态 |", "| --- | --- | --- | --- |"]
        for item in store["items"]:
            p=item["price"]
            prices="；".join(k+" "+str(v)+"元" for k,v in p["values"].items()) or "未明确"
            if p["unit"] in {"小时","30分钟"}:prices+="/"+p["unit"]
            if p.get("note"):prices+="（"+p["note"]+"）"
            lines.append("| %s | %s | %s | %s |" % (item["name"],item["duration"],prices,"上架" if item["enabled"] else "待确认，不上架"))
    (target/"品项资料核对表.md").write_text("\n".join(lines)+"\n",encoding="utf-8")
    print(f"Prepared {len(services)} service definitions, {len(stores)} independent branches, "+str(sum(i['enabled'] for s in stores for i in s['items']))+" active branch items, "+str(sum(not i['enabled'] for s in stores for i in s['items']))+" pending items.")


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-dir",type=Path,required=True)
    parser.add_argument("--output-dir",type=Path,default=ROOT/"initial-data/client-services-20261108")
    args=parser.parse_args()
    prepare(args.source_dir.resolve(),args.output_dir.resolve())
