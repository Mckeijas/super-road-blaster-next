import sys, asyncio
from playwright.async_api import async_playwright
async def main(jobs):
    async with async_playwright() as p:
        b = await p.chromium.launch(executable_path='/opt/pw-browsers/chromium' if False else None, args=['--use-gl=angle','--use-angle=swiftshader','--enable-unsafe-swiftshader'])
        pg = await b.new_page(viewport={'width': 1483, 'height': 1080})
        for url, out in jobs:
            await pg.goto(url)
            await pg.wait_for_function("document.title.length>0", timeout=60000)
            t = await pg.title()
            if t != 'done': print(out, t); continue
            await pg.locator('#c').screenshot(path=out)
        await b.close()
jobs = [l.split() for l in sys.stdin.read().strip().splitlines()]
asyncio.run(main(jobs))
