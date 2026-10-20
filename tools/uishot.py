import asyncio, sys
from playwright.async_api import async_playwright
# usage: uishot.py url out.png "keys,comma,separated" [bgimage]
async def main(url, out, keys, bg):
    async with async_playwright() as p:
        b = await p.chromium.launch(args=['--use-gl=angle','--use-angle=swiftshader','--enable-unsafe-swiftshader'])
        pg = await b.new_page(viewport={'width':1920,'height':1080})
        errs=[]
        pg.on('console', lambda m: errs.append(m.text) if m.type=='error' else None)
        pg.on('pageerror', lambda e: errs.append(str(e)))
        await pg.goto(url); await pg.wait_for_timeout(600)
        if bg: await pg.evaluate("document.documentElement.style.background='url(%s) center/contain no-repeat #000'" % bg)
        for k in [k for k in keys.split(',') if k]:
            await pg.keyboard.press(k); await pg.wait_for_timeout(120)
        await pg.wait_for_timeout(900)
        await pg.screenshot(path=out)
        print(out, 'errors:', errs)
        await b.close()
asyncio.run(main(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv)>3 else '', sys.argv[4] if len(sys.argv)>4 else ''))
