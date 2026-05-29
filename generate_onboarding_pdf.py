"""Generate the New-Shop Onboarding Playbook PDF."""
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.units import mm
from reportlab.lib.colors import HexColor, black
from reportlab.lib.enums import TA_LEFT
from reportlab.platypus import (SimpleDocTemplate, Paragraph, Spacer, Preformatted,
                                Table, TableStyle, PageBreak, KeepTogether)

OUT = r"D:\Pawnbroking\PawnBrokingMobApp\New_Shop_Onboarding.pdf"

# ───── Styles ─────────────────────────────────────────────────────────────────
ss = getSampleStyleSheet()
GOLD = HexColor("#B8860B")
DARK = HexColor("#1E2A4A")
GREY = HexColor("#555555")
LIGHTBG = HexColor("#F4F1E8")

H_TITLE = ParagraphStyle("h_title", parent=ss["Title"], textColor=GOLD,
                         fontSize=22, leading=26, spaceAfter=4, alignment=TA_LEFT)
H_SUB   = ParagraphStyle("h_sub", parent=ss["Normal"], textColor=GREY,
                         fontSize=11, leading=14, spaceAfter=16)
H1 = ParagraphStyle("h1", parent=ss["Heading1"], textColor=DARK,
                    fontSize=15, leading=18, spaceBefore=14, spaceAfter=6,
                    keepWithNext=True)
H2 = ParagraphStyle("h2", parent=ss["Heading2"], textColor=DARK,
                    fontSize=12, leading=15, spaceBefore=10, spaceAfter=4,
                    keepWithNext=True)
BODY = ParagraphStyle("body", parent=ss["BodyText"], fontSize=10, leading=14,
                      spaceAfter=4)
BULLET = ParagraphStyle("bul", parent=BODY, leftIndent=14, bulletIndent=2,
                        spaceAfter=2)
CODE = ParagraphStyle("code", parent=ss["Code"], fontName="Courier",
                      fontSize=8.5, leading=11, leftIndent=8, rightIndent=8,
                      backColor=LIGHTBG, borderColor=HexColor("#D0CBB8"),
                      borderWidth=0.5, borderPadding=6, spaceBefore=2,
                      spaceAfter=8)
NOTE = ParagraphStyle("note", parent=BODY, leftIndent=10,
                      borderColor=GOLD, borderWidth=0, leading=13,
                      textColor=HexColor("#333333"), fontSize=9.5)

def code(text):
    return Preformatted(text, CODE)

def h(t, s=H1): return Paragraph(t, s)
def p(t):       return Paragraph(t, BODY)
def b(t):       return Paragraph("• " + t, BULLET)

# ───── Build ──────────────────────────────────────────────────────────────────
doc = SimpleDocTemplate(OUT, pagesize=A4,
                        leftMargin=18*mm, rightMargin=18*mm,
                        topMargin=18*mm, bottomMargin=18*mm,
                        title="New Shop Onboarding Playbook",
                        author="Pawnbroking Cloud")
story = []

# Cover
story += [
    Paragraph("New Shop Onboarding", H_TITLE),
    Paragraph("Step-by-step playbook for setting up a new shop "
              "(e.g. <i>Balamurugan</i>) on the pawnbroking cloud + "
              "Magizhchi Share pipeline.", H_SUB),
]

# Phase A
story += [h("Phase A &mdash; Cloud setup (admin, ~5 minutes)"),
          p("Done once from any computer with Railway access. "
            "Substitute <b>balamurugan</b> with the real shop_id throughout.")]

story += [h("A1. Add the shop as a tenant", H2),
          p("<b>Option 1 (easy)</b> &mdash; redeploy with the shop in env var. "
            "Railway &rarr; cloud-api service &rarr; Variables &rarr; set:"),
          code("TENANTS=alwarpuram,annanagar,mylocal,balamurugan"),
          p("Redeploy. On boot the log prints:"),
          code("provisioning tenant schema 'balamurugan'\n"
               "API KEY for shop 'balamurugan' = BALAMURUGAN_xxxxxxxxxxxxxxxx   <-- SAVE THIS"),
          p("<b>Option 2 (SQL only, no redeploy)</b> &mdash; run on Railway Postgres:"),
          code("INSERT INTO public.tenants(shop_id, schema_name, display_name, primary_email)\n"
               "VALUES ('balamurugan', 'balamurugan', 'Balamurugan',\n"
               "        'balamurugan_owner@gmail.com');\n\n"
               "CREATE SCHEMA IF NOT EXISTS balamurugan;\n"
               "-- then run the contents of db/tenant/tenant.sql with\n"
               "-- search_path = balamurugan, public  to provision tables.\n\n"
               "INSERT INTO public.shop_credentials(api_key, shop_id, label)\n"
               "VALUES ('BALAMURUGAN_' ||\n"
               "        replace(gen_random_uuid()::text, '-', ''),\n"
               "        'balamurugan', 'desktop agent')\n"
               "RETURNING api_key;   -- SAVE THE PRINTED KEY")]

story += [h("A2. Set the primary email", H2),
          p("The mobile app for balamurugan can only be signed in with this email. "
            "Set it if you didn't above:"),
          code("UPDATE public.tenants\n"
               "   SET primary_email = 'balamurugan_owner@gmail.com'\n"
               " WHERE shop_id = 'balamurugan';")]

# Phase B
story += [h("Phase B &mdash; Owner registers on Magizhchi Share (~3 min)"),
          p("Gives them a Magizhchi account so the cloud can later store bills, "
            "images and backup files on their behalf."),
          b("Open <b>https://box.magizhchi.software</b> in any browser."),
          b("Sign up with mobile + email = <b>balamurugan_owner@gmail.com</b> + display name."),
          b("Verify the OTP they receive."),
          Spacer(1, 4),
          Paragraph("<b>Don't issue API tokens manually.</b> The cloud auto-mints one on "
                    "first OTP login from the phone (Phase D2).", NOTE)]

# Phase C
story += [PageBreak(),
          h("Phase C &mdash; Shop PC setup (Windows, ~15 min)")]

story += [h("C1. PostgreSQL", H2),
          b("Install Postgres 18 for Windows. Default port 5432, set a password."),
          b("Create the database:"),
          code('& "C:\\Program Files\\PostgreSQL\\18\\bin\\psql.exe" '
               '-U postgres -c "CREATE DATABASE pawnbroking"'),
          b("Restore the desktop app's schema/data from your master SQL dump.")]

story += [h("C2. Java 17+", H2),
          b("Install Eclipse Temurin JDK 17 (or 21). Verify:"),
          code("java -version")]

story += [h("C3. Desktop pawnbroking app", H2),
          p("Install as usual. On first run it'll seed CMP1 in the <code>company</code> "
            "table. The user can change/add companies later via the desktop UI.")]

story += [h("C4. Sync agent (Windows service)", H2),
          b("Copy <code>pawnbroking-sync-agent-1.0.0-shaded.jar</code>, "
            "<code>pawnbroking-sync.exe</code> (WinSW renamed), and "
            "<code>pawnbroking-sync.xml</code> to <code>C:\\PawnSync\\</code>."),
          b("Create <code>C:\\ProgramData\\PawnBroking\\sync.properties</code>:"),
          code("db.url=jdbc:postgresql://localhost:5432/pawnbroking\n"
               "db.user=postgres\n"
               "db.password=<their postgres password>\n\n"
               "cloud.url=https://devpawn.magizhchi.academy\n"
               "cloud.api_key=<paste BALAMURUGAN_xxx api_key from Phase A1>\n"
               "shop.id=balamurugan\n\n"
               "batch.size=200\n"
               "poll.interval.ms=5000\n"
               "health.port=17654\n"
               "schema.guard.interval.ms=30000\n"
               "image.scan.interval.ms=60000"),
          b("Install + start the service (elevated PowerShell):"),
          code("cd C:\\PawnSync\n"
               ".\\pawnbroking-sync.exe install\n"
               ".\\pawnbroking-sync.exe start"),
          b("Health check:"),
          code("curl http://127.0.0.1:17654/health     # should return OK"),
          Spacer(1, 4),
          Paragraph("SchemaGuard installs <code>sync_outbox</code>, triggers, "
                    "<code>sync_image_uploads</code>, and <code>sync_backup_uploads</code> "
                    "automatically on first run.", NOTE)]

story += [h("C5. Backfill historical data (only if the desktop already has bills)", H2),
          p("On the shop's local Postgres:"),
          code('& "C:\\Program Files\\PostgreSQL\\18\\bin\\psql.exe" -U postgres '
               '-d pawnbroking `\n'
               '   -f "C:\\path\\to\\pawnbroking-outbox\\migrations\\BACKFILL__existing_rows.sql"'),
          p("Agent picks up new outbox rows within 5 s. At box rate-limit "
            "(60 req/min), ~1000 bills + their images take ~17 min to fully sync.")]

# Phase D
story += [PageBreak(),
          h("Phase D &mdash; Owner's phone (~2 min)")]

story += [h("D1. Build the per-shop APK", H2),
          p("<code>SHOP_ID</code> is hardcoded per build. Edit one line in "
            "<code>PawnAndroidApp/app/src/main/java/com/pawnbroking/app/config/AppConfig.java</code>:"),
          code('public static final String SHOP_ID = "balamurugan";'),
          p("Build:"),
          code("cd D:\\Pawnbroking\\PawnBrokingMobApp\\PawnAndroidApp\n"
               ".\\gradlew.bat assembleDebug"),
          p("APK lands at <code>app\\build\\outputs\\apk\\debug\\app-debug.apk</code>. "
            "Send via Gmail / WhatsApp / USB.")]

story += [h("D2. Install + first login (auto-mints the Magizhchi token)", H2),
          b("Owner installs APK (allow 'install unknown apps')."),
          b("Email <b>balamurugan_owner@gmail.com</b> &rarr; <b>Send OTP</b> &rarr; "
            "enter 6-digit code &rarr; <b>Verify &amp; Sign In</b>."),
          b("Behind the scenes (no action needed):"),
          Paragraph("&nbsp;&nbsp;&nbsp;&ndash; Cloud-api receives box's accessToken from OTP verify.",
                    BULLET),
          Paragraph("&nbsp;&nbsp;&nbsp;&ndash; Cloud-api calls "
                    "<code>POST https://box.magizhchi.software/api/api-tokens</code> "
                    "with that JWT.", BULLET),
          Paragraph("&nbsp;&nbsp;&nbsp;&ndash; Box returns an "
                    "<code>mbk_&hellip;</code> token + the user's personal drive id.",
                    BULLET),
          Paragraph("&nbsp;&nbsp;&nbsp;&ndash; Cloud-api stores both in "
                    "<code>public.tenants</code> for balamurugan.", BULLET)]

story += [h("D3. Verify everything's wired", H2),
          p("From your laptop, on Railway Postgres:"),
          code("SELECT shop_id, primary_email,\n"
               "       magizhchi_token IS NOT NULL AS has_box_token,\n"
               "       magizhchi_conversation_id\n"
               "  FROM public.tenants WHERE shop_id='balamurugan';"),
          p("Expected: row exists, primary_email set, "
            "<code>has_box_token = true</code>, conv_id populated.")]

# Phase E
story += [PageBreak(),
          h("Phase E &mdash; Backup folder sync (automatic)"),
          p("From this build forward, the sync-agent <b>also</b> uploads every file in "
            "the shop's <code>company.backup_file_path</code> folder to the Magizhchi "
            "box under <code>backups/&lt;companyId&gt;/&lt;relative-path&gt;/</code>."),
          b("No extra config &mdash; the column is read from the local "
            "<code>company</code> table every scan tick (default 60 s)."),
          b("Tracked in local <code>sync_backup_uploads</code> table so each file uploads "
            "only once. Re-running the desktop's backup procedure to overwrite a file "
            "will re-upload (the cloud upserts under the same key)."),
          b("Subject to the same 60 req/min box rate limit as bill images."),
          Spacer(1, 4),
          Paragraph("If <code>company.backup_file_path</code> is blank or the directory "
                    "doesn't exist, the watcher silently skips it &mdash; no errors.",
                    NOTE)]

# Phase F
story += [h("Phase F &mdash; That's it"),
          b("Shop owner uses the desktop app normally."),
          b("Every new bill / customer / image syncs to cloud in ~5 s (events) "
            "and ~1 min (images / backup files)."),
          b("Phone shows real-time data + bell notifications, charts refresh per company.")]

# Reference table
story += [PageBreak(),
          h("Reference &mdash; Files shipped to each new shop's PC")]

ref_rows = [
    ["File", "From your repo", "Lands on shop PC"],
    ["pawnbroking-sync-agent-1.0.0-shaded.jar",
     "pawnbroking-sync-agent/target/",
     "C:\\PawnSync\\"],
    ["pawnbroking-sync.exe (WinSW renamed)",
     "pawnbroking-sync-agent/winsw/",
     "C:\\PawnSync\\"],
    ["pawnbroking-sync.xml",
     "pawnbroking-sync-agent/winsw/",
     "C:\\PawnSync\\"],
    ["sync.properties (per-shop)",
     "template: pawnbroking-sync-agent/sync.properties.sample",
     "C:\\ProgramData\\PawnBroking\\sync.properties"],
    ["BACKFILL__existing_rows.sql",
     "pawnbroking-outbox/migrations/",
     "run locally via psql"],
    ["app-debug.apk (per-shop, edit SHOP_ID first)",
     "PawnAndroidApp/app/build/outputs/apk/debug/",
     "sideload to owner's Android phone"],
]

tbl = Table(ref_rows, colWidths=[55*mm, 55*mm, 60*mm], hAlign="LEFT")
tbl.setStyle(TableStyle([
    ("BACKGROUND", (0,0), (-1,0), DARK),
    ("TEXTCOLOR",  (0,0), (-1,0), GOLD),
    ("FONTNAME",   (0,0), (-1,0), "Helvetica-Bold"),
    ("FONTSIZE",   (0,0), (-1,-1), 8.5),
    ("VALIGN",     (0,0), (-1,-1), "TOP"),
    ("BOX",        (0,0), (-1,-1), 0.5, GREY),
    ("INNERGRID",  (0,0), (-1,-1), 0.25, GREY),
    ("BACKGROUND", (0,1), (-1,-1), HexColor("#FAFAF5")),
    ("LEFTPADDING",  (0,0), (-1,-1), 5),
    ("RIGHTPADDING", (0,0), (-1,-1), 5),
    ("TOPPADDING",   (0,0), (-1,-1), 4),
    ("BOTTOMPADDING",(0,0), (-1,-1), 4),
]))
story += [tbl]

# Forward-looking note
story += [Spacer(1, 12),
          h("Future improvement &mdash; one APK for every shop", H2),
          p("Today you build a separate APK per shop because <code>SHOP_ID</code> is "
            "hardcoded. A cleaner model: the Login screen asks for email first, cloud-api "
            "tells the app which shop that email belongs to (using the existing "
            "<code>primary_email</code> column), and the app stores it locally for the "
            "session. Then one APK works for every shop forever and onboarding skips "
            "Phase D1 entirely.")]

doc.build(story)
print("Wrote", OUT)
