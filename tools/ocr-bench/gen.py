"""Render phone-like screens with Chromium and keep every text element's box as ground truth.

Screens are generic app layouts (settings list, feed with cards, chat, shop, video page, dark game HUD, form, dialog,
table of numbers, English page). Text is random from a mixed corpus, so no layout is tuned to one app.
Output (in the current directory): screens/NNN.png + screens/NNN.json ({layout, gt: [{text, box:[x0,y0,x1,y1]}]}).
Usage: python gen.py [count] [seed]
"""
import json, random, sys, os
from playwright.sync_api import sync_playwright

random.seed(int(sys.argv[2]) if len(sys.argv) > 2 else 7)
N = int(sys.argv[1]) if len(sys.argv) > 1 else 40
HARD = os.environ.get("HARD") == "1"
OUT = "screens-hard" if HARD else "screens"
os.makedirs(OUT, exist_ok=True)

ZH = ("设置 蓝牙 无线局域网 移动网络 通知 显示与亮度 声音和振动 电池 存储 隐私 安全 应用管理 系统更新 关于手机 账号 同步 "
      "推荐 关注 热门 视频 直播 附近 消息 我的 首页 购物车 订单 待付款 待发货 待收货 评价 退款 售后 客服 收藏 足迹 优惠券 红包 "
      "搜索 历史记录 清空 取消 确定 完成 下一步 返回 分享 举报 复制链接 保存图片 立即购买 加入购物车 领取 已领取 去使用 "
      "签到 每日任务 积分 兑换 会员 开通 续费 限时优惠 新品 包邮 满减 秒杀 预售 到货提醒 已售罄 库存紧张 "
      "今天 昨天 星期三 上午 下午 晚上 刚刚 分钟前 小时前 已读 未读 草稿 发送 语音 图片 文件 位置 转账 "
      "天气 多云 晴 小雨 空气质量 良 湿度 风力 日出 日落 体感温度 降水概率 "
      "播放 暂停 下一集 倍速 弹幕 清晰度 全屏 投屏 选集 简介 评论 点赞 转发 弹幕已关闭 "
      "登录 注册 手机号 验证码 获取验证码 密码 忘记密码 用户协议 隐私政策 同意并继续 其他登录方式 "
      "关卡 体力 金币 钻石 背包 商店 任务 排行榜 开始游戏 继续 重新开始 胜利 失败 奖励 领取奖励 "
      "鬓 饕餮 甄嬛 龘 囧 犇 翡翠 琵琶 蝌蚪 嗑瓜子 薅羊毛 氪金 栀子 窈窕 踌躇 婺源 亳州 涪陵").split()
EN = ("Settings Bluetooth Wi-Fi Network Notifications Display Battery Storage Privacy Security Apps Update About "
      "Home Search Profile Messages Cart Orders Wishlist Coupons Checkout Continue Cancel Done Next Back Share "
      "Play Pause Subscribe Comments Likes Download Upload Account Sign in Sign up Password Forgot Terms Level Score "
      "Today Yesterday Weather Cloudy Humidity Sunrise Free shipping In stock Sold out Add to cart Buy now").split()
NAMES = "张伟 王芳 李娜 刘洋 陈静 小王 阿杰 Lily Tom 妈妈 工作群 家庭群 技术交流 周末去哪儿".split()


def word():
    r = random.random()
    if r < 0.62: return random.choice(ZH)
    if r < 0.80: return random.choice(EN)
    if r < 0.88: return random.choice(["¥%d.%02d" % (random.randint(1, 9999), random.randint(0, 99)), "%d%%" % random.randint(1, 100),
                                       "%02d:%02d" % (random.randint(0, 23), random.randint(0, 59)), str(random.randint(1, 99999)),
                                       "%d月%d日" % (random.randint(1, 12), random.randint(1, 28)), "%.1f万" % (random.random() * 99),
                                       "%d°C" % random.randint(-5, 38), "v%d.%d.%d" % (random.randint(1, 9), random.randint(0, 20), random.randint(0, 9))])
    return random.choice(ZH) + random.choice(ZH)


def phrase(n=None):
    n = n or random.randint(1, 3)
    out = ""
    for i in range(n):
        w = word()
        if out and (out[-1].isascii() and out[-1].isalnum() or w[0].isascii() and w[0].isalnum()):
            out += " "
        out += w
    return out


def sentence():
    s = "".join(random.choice(ZH) for _ in range(random.randint(3, 7)))
    if random.random() < 0.3: s += "，" + "".join(random.choice(ZH) for _ in range(random.randint(2, 4)))
    if random.random() < 0.2: s += " " + random.choice(EN)
    return s


def t(s, cls=""):
    return f'<span class="t {cls}">{s}</span>'


PAL = [("#ffffff", "#1a1a1a", "#8a8a8a"), ("#f5f5f7", "#111", "#999"), ("#101014", "#f2f2f2", "#9a9aa2"),
       ("#fff8f0", "#3a2a1a", "#a08060"), ("#eef5ff", "#123", "#6a7a90"), ("#1b1d2a", "#e8e8ff", "#8888aa")]


def statusbar():
    return f'<div class="sb">{t("%02d:%02d" % (random.randint(0,23), random.randint(0,59)))}<span style="flex:1"></span>{t(str(random.randint(5,100))+"%")}</div>'


def topbar():
    return f'<div class="top">{t(phrase(1), "h1")}<span style="flex:1"></span>{t(random.choice(["搜索","编辑","更多","完成","Search","Edit"]), "act")}</div>'


def lay_settings():
    rows = "".join(f'<div class="row">{t(phrase(random.randint(1,2)))}<span style="flex:1"></span>{t(random.choice(["已开启","关闭",phrase(1),"›"]), "sub")}</div>' +
                   (f'<div class="row2">{t(sentence(), "sub small")}</div>' if random.random() < 0.3 else "") for _ in range(random.randint(10, 14)))
    return topbar() + f'<div class="grp">{rows}</div>'


def lay_feed():
    cards = ""
    for _ in range(random.randint(4, 6)):
        img = f'<div class="img" style="background:linear-gradient({random.randint(0,360)}deg,hsl({random.randint(0,360)},60%,60%),hsl({random.randint(0,360)},60%,35%))">' + \
              (t(phrase(1), "onimg") if random.random() < 0.6 else "") + '</div>'
        cards += f'<div class="card">{img}{t(sentence(), "title")}<div class="meta">{t(random.choice(NAMES), "sub small")}{t(word() if random.random()<.5 else "%d赞" % random.randint(1,9999), "sub small")}</div></div>'
    tabs = "".join(t(w, "tab" + (" on" if i == 1 else "")) for i, w in enumerate(random.sample(["关注", "推荐", "热门", "视频", "附近", "直播", "For you"], 5)))
    return f'<div class="tabs">{tabs}</div><div class="grid">{cards}</div>'


def lay_chat():
    msgs = ""
    for _ in range(random.randint(7, 10)):
        me = random.random() < 0.5
        msgs += f'<div class="msg {"me" if me else ""}">{t(sentence() if random.random()<.7 else phrase(), "bubble")}</div>'
        if random.random() < 0.25: msgs += f'<div class="time">{t("%s %02d:%02d" % (random.choice(["昨天","今天","星期三",""]), random.randint(0,23), random.randint(0,59)), "sub small")}</div>'
    return f'<div class="top">{t(random.choice(NAMES), "h1")}</div>' + msgs + f'<div class="input">{t(random.choice(["发消息…","说点什么","Message"]), "sub")}{t("发送", "btn")}</div>'


def lay_shop():
    items = ""
    for _ in range(random.randint(4, 6)):
        items += f'<div class="item"><div class="thumb" style="background:hsl({random.randint(0,360)},40%,80%)"></div><div>{t(sentence(), "title")}<br>' \
                 f'{t("¥%d" % random.randint(1, 9999), "price")} {t(random.choice(["包邮","满300减40","已售%d+" % random.randint(1,999),"新品"]), "tag")}<br>{t(phrase(2), "sub small")}</div></div>'
    return f'<div class="search">{t(random.choice(["搜索商品","耳机","Search products"]), "sub")}</div>' + items + \
           f'<div class="bottom">{t("加入购物车", "btn2")}{t("立即购买", "btn")}</div>'


def lay_video():
    over = "".join(f'<div class="danmaku" style="top:{random.randint(5,85)}%;left:{random.randint(0,50)}%">{t(phrase(), "dm")}</div>' for _ in range(random.randint(2, 5)))
    return f'<div class="video" style="background:linear-gradient(135deg,#223,#{random.randint(0,0xffffff):06x})">{over}{t("%02d:%02d / %02d:%02d" % (0, random.randint(0,59), random.randint(1,59), random.randint(0,59)), "onimg small")}</div>' + \
           f'<div class="pad">{t(sentence(), "title")}<br>{t("%.1f万播放 · %s" % (random.random()*99, random.choice(["2小时前","昨天","3天前"])), "sub small")}</div>' + \
           "".join(f'<div class="row">{t(random.choice(NAMES), "sub")} {t(sentence())}</div>' for _ in range(random.randint(4, 7)))


def lay_game():
    hud = f'<div class="hud">{t("金币 %d" % random.randint(0,99999), "gold")}{t("钻石 %d" % random.randint(0,999), "gold")}{t("体力 %d/%d" % (random.randint(0,120), 120), "gold")}</div>'
    btns = "".join(f'<div class="gbtn" style="background:hsl({random.randint(0,360)},70%,45%)">{t(random.choice(["开始游戏","商店","任务","背包","排行榜","Level %d" % random.randint(1,99), "领取奖励"]), "gtext")}</div>' for _ in range(random.randint(4, 7)))
    return f'<div class="game" style="background:radial-gradient(circle at {random.randint(10,90)}% 30%,#{random.randint(0,0xffffff):06x},#111)">{hud}{t("第 %d 关" % random.randint(1, 300), "big")}{btns}</div>'


def lay_form():
    f = "".join(f'<div class="field">{t(w, "label")}<div class="box">{t(random.choice([phrase(1), "请输入"+w, "1%010d" % random.randint(0, 10**10-1)]), "sub")}</div></div>'
                for w in random.sample(["手机号", "验证码", "密码", "昵称", "地址", "邮箱", "Email", "Name"], 4))
    return f'<div class="top">{t(random.choice(["登录","注册","编辑资料","Sign in"]), "h1")}</div>' + f + \
           f'<div class="bigbtn">{t(random.choice(["登录","下一步","同意并继续","Continue"]), "btntext")}</div>{t(random.choice(["忘记密码？","其他登录方式","用户协议与隐私政策"]), "sub small center")}'


def lay_dialog():
    return lay_settings() + f'<div class="scrim"></div><div class="dlg">{t(phrase(2), "h1")}<br>{t(sentence(), "")}<br>{t(sentence(), "sub small")}' \
                            f'<div class="dbtn">{t(random.choice(["取消","以后再说","Cancel"]))}{t(random.choice(["确定","立即更新","允许","OK"]), "act")}</div></div>'


def lay_table():
    rows = "".join("<tr>" + "".join(f"<td>{t(c)}</td>" for c in [random.choice(ZH), "%d" % random.randint(0, 99999), "%.2f" % (random.random()*1000), "%+.2f%%" % (random.random()*20-10)]) + "</tr>"
                   for _ in range(random.randint(12, 16)))
    return topbar() + f'<table>{rows}</table>'


def lay_english():
    return f'<div class="top">{t(" ".join(random.sample(EN, 2)), "h1")}</div>' + "".join(
        f'<div class="row">{t(" ".join(random.sample(EN, random.randint(2, 5))))}</div><div class="row2">{t(" ".join(random.sample(EN, random.randint(4, 8))).lower(), "sub small")}</div>' for _ in range(random.randint(7, 10)))


LAYOUTS = [lay_settings, lay_feed, lay_chat, lay_shop, lay_video, lay_game, lay_form, lay_dialog, lay_table, lay_english]

CSS = """
*{box-sizing:border-box;margin:0;padding:0} body{width:393px;height:873px;overflow:hidden;font-family:%FONT%;background:var(--bg);color:var(--fg);font-size:%FS%px;position:relative}
.sb{display:flex;padding:6px 18px;font-size:12px;font-weight:600}
.top{display:flex;align-items:center;padding:10px 16px}.h1{font-size:20px;font-weight:700}.act{color:#7a5af8;font-weight:600}
.sub{color:var(--sub)}.small{font-size:11px}.center{display:block;text-align:center;margin-top:10px}
.grp{margin:4px 12px;border-radius:14px;background:var(--card)}.row{display:flex;padding:12px 14px;border-bottom:1px solid rgba(128,128,128,.12)}.row2{padding:0 14px 8px}
.tabs{display:flex;gap:18px;padding:8px 16px}.tab{color:var(--sub)}.tab.on{color:var(--fg);font-weight:700;font-size:17px}
.grid{display:grid;grid-template-columns:1fr 1fr;gap:8px;padding:8px}.card{background:var(--card);border-radius:10px;overflow:hidden}
.img{height:120px;display:flex;align-items:flex-end;padding:6px}.onimg{color:#fff;text-shadow:0 1px 2px #0008}.title{display:block;padding:6px 8px 2px;font-weight:500}
.meta{display:flex;justify-content:space-between;padding:2px 8px 8px}
.msg{display:flex;padding:6px 12px}.msg.me{justify-content:flex-end}.bubble{background:var(--card);padding:8px 12px;border-radius:12px;max-width:72%}.me .bubble{background:#95ec69;color:#111}
.time{text-align:center;padding:4px}.input{position:absolute;bottom:0;left:0;right:0;display:flex;justify-content:space-between;padding:12px 16px;background:var(--card)}
.btn{background:#ff5000;color:#fff;padding:6px 14px;border-radius:16px}.btn2{background:#ffb000;color:#fff;padding:6px 14px;border-radius:16px}
.search{margin:8px 12px;padding:8px 14px;border-radius:18px;background:var(--card)}.item{display:flex;gap:10px;padding:10px 12px}.thumb{width:96px;height:96px;border-radius:8px;flex:none}
.price{color:#ff3b30;font-weight:700;font-size:17px}.tag{color:#ff5000;border:1px solid #ff5000;font-size:10px;padding:0 3px;border-radius:3px}
.bottom{position:absolute;bottom:0;left:0;right:0;display:flex;justify-content:flex-end;gap:8px;padding:10px;background:var(--card)}
.video{height:221px;position:relative;display:flex;align-items:flex-end;padding:8px}.danmaku{position:absolute}.dm{color:#fff;text-shadow:0 0 2px #000;font-size:14px}.pad{padding:10px 14px}
.game{height:100%;padding:20px;display:flex;flex-direction:column;align-items:center;gap:14px}.hud{display:flex;gap:10px;width:100%;justify-content:space-between}
.gold{color:#ffd54a;font-weight:700;font-size:13px;text-shadow:0 1px 0 #000}.big{color:#fff;font-size:30px;font-weight:900;text-shadow:0 2px 4px #000}
.gbtn{padding:10px 26px;border-radius:14px;box-shadow:0 3px 0 #0006}.gtext{color:#fff;font-weight:800;font-size:17px}
.field{padding:10px 16px}.label{font-size:13px}.box{margin-top:6px;padding:10px;border-radius:8px;background:var(--card)}
.bigbtn{margin:16px;padding:12px;text-align:center;background:#7a5af8;border-radius:22px}.btntext{color:#fff;font-weight:600}
.scrim{position:absolute;inset:0;background:#0007}.dlg{position:absolute;left:28px;right:28px;top:300px;background:var(--card);border-radius:18px;padding:20px}
.dbtn{display:flex;justify-content:space-between;margin-top:16px}
table{width:100%;border-collapse:collapse;font-size:13px}td{padding:7px 10px;border-bottom:1px solid rgba(128,128,128,.15)}
"""

FONTS = ["'Noto Sans CJK SC'", "'Noto Serif CJK SC'", "'Noto Sans CJK SC', 'DejaVu Sans'"]

CHROMIUM = os.environ.get("CHROMIUM_BIN", "/usr/bin/chromium")
with sync_playwright() as p:
    b = p.chromium.launch(executable_path=CHROMIUM if os.path.exists(CHROMIUM) else None, args=["--no-sandbox"])
    pg = b.new_page(viewport={"width": 393, "height": 873}, device_scale_factor=2.75)
    for i in range(N):
        lay = LAYOUTS[i % len(LAYOUTS)]
        bg, fg, sub = random.choice(PAL) if lay is not lay_game else ("#111", "#fff", "#aaa")
        card = "#ffffff" if bg in ("#f5f5f7", "#eef5ff", "#fff8f0") else ("#22232b" if bg.startswith("#1") else "#f3f3f3")
        css = CSS.replace("%FONT%", FONTS[0] if i % 7 else FONTS[1]).replace("%FS%", str(random.choice([11, 12]) if HARD else random.choice([13, 14, 15, 16])))
        if HARD:
            # Hard set: tiny captions, faint grey text, text over noisy pictures.
            css = css.replace(".small{font-size:11px}", ".small{font-size:9px}").replace(".h1{font-size:20px", ".h1{font-size:16px")
            sub = {"#8a8a8a": "#c4c4c4", "#999": "#c8c8c8", "#9a9aa2": "#5a5a62", "#a08060": "#d0b898", "#6a7a90": "#a8b4c4", "#8888aa": "#55557a"}.get(sub, sub)
            css += ".img,.video{background-image:repeating-linear-gradient(45deg,#fff3 0 3px,#0003 3px 7px)!important}"
        html = f"<html><head><style>:root{{--bg:{bg};--fg:{fg};--sub:{sub};--card:{card}}}{css}</style></head><body>{statusbar()}{lay()}</body></html>"
        pg.set_content(html)
        pg.wait_for_timeout(50)
        gt = pg.evaluate("""() => { const out=[]; const H=innerHeight, W=innerWidth; const sc=document.querySelector('.scrim');
            document.querySelectorAll('.t').forEach(e => { const r=e.getBoundingClientRect(); const s=e.innerText.trim();
              if(!s||r.width<2||r.top<0||r.bottom>H||r.left<0||r.right>W) return;
              out.push({text:s, box:[r.left,r.top,r.right,r.bottom], dim: !!(sc && !e.closest('.dlg'))}); }); return out; }""")
        for g in gt: g["box"] = [round(v * 2.75) for v in g["box"]]
        pg.screenshot(path=f"{OUT}/{i:03d}.png")
        json.dump({"layout": lay.__name__, "gt": gt}, open(f"{OUT}/{i:03d}.json", "w"), ensure_ascii=False)
    b.close()
print("ok", N)
