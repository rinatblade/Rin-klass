package com.rin.klass;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.widget.ImageViewCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements Catcher.Host {
    private static final int TAB_SITE = 0;
    private static final int TAB_GIFTS = 1;
    private static final int TAB_STICKERS = 2;
    private static final int TAB_SETTINGS = 3;

    private static final int REQ_STORAGE = 21;
    private static final int REQ_NOTIF = 22;
    private static final int REQ_WA = 23;

    private static final String EMOJI = "🎁";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<GiftStore.Gift> pendingExport = new CopyOnWriteArrayList<>();

    private int tab = TAB_SITE;
    private View root;
    private View[] tabViews;
    private View[] navPills;
    private ImageView[] navIcons;
    private TextView[] navLabels;

    // сайт
    private WebView web;
    private Catcher catcher;
    private String catcherJs = "";
    private volatile boolean okPage = false;
    private volatile String pageUrl = "";
    private ProgressBar pageProgress;
    private TextView pageTitle;
    private TextView statusLine;
    private TextView hintPill;
    private MaterialSwitch swCatcher;
    private MaterialSwitch swCatcher2;
    private boolean syncing = false;

    // подарки
    private RecyclerView giftsGrid;
    private View giftsEmpty;
    private TextView giftsCount;
    private GiftAdapter giftAdapter;

    // стикеры
    private RecyclerView stickersGrid;
    private TextView stickersEmpty;
    private TextView packName;
    private TextView packStatus;
    private ProgressBar packProgress;
    private MaterialButton btnWa;
    private MaterialButton btnWaBiz;
    private StickerAdapter stickerAdapter;
    private PackStore packs;

    // настройки
    private LinearLayout themeRow;
    private TextView themeValue;
    private TextView fontSample;
    private TextView fontValue;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
                    if ("catcher".equals(key)) {
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                applyCatcherState(false);
                            }
                        });
                    }
                }
            };

    // ------------------------------------------------------------------ жизненный цикл

    @Override
    protected void onCreate(Bundle state) {
        ThemeManager.apply(this);
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        root = findViewById(R.id.root);
        packs = new PackStore(this);
        catcher = new Catcher(this, this);
        catcherJs = readAsset("catcher.js");
        GiftStore.trimProbeCache(this);

        initTabs();
        initSite();
        initGifts();
        initStickers();
        initSettings();

        int start = state != null ? state.getInt("tab", TAB_SITE) : TAB_SITE;
        showTab(start);
        UiFont.apply(this, root);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (tab == TAB_SITE && web.canGoBack()) {
                    web.goBack();
                } else if (tab != TAB_SITE) {
                    showTab(TAB_SITE);
                } else {
                    moveTaskToBack(true);
                }
            }
        });

        askNotifications();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", tab);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Prefs.raw(this).registerOnSharedPreferenceChangeListener(prefListener);
        if (Prefs.keepBg(this)) {
            KlassService.start(this);
        } else {
            KlassService.stop(this);
        }
        applyCatcherState(false);
    }

    @Override
    protected void onStop() {
        Prefs.raw(this).unregisterOnSharedPreferenceChangeListener(prefListener);
        CookieManager.getInstance().flush();
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
            web.onResume();
        }
    }

    @Override
    protected void onPause() {
        // в фоновом режиме страница не замирает: загрузки идут в отдельных потоках,
        // а сам браузер просто приостанавливается, пока экран не виден
        if (web != null) {
            web.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.removeJavascriptInterface("KlassBridge");
            web.destroy();
        }
        super.onDestroy();
    }

    private String readAsset(String name) {
        try (java.io.InputStream in = getAssets().open(name)) {
            return new String(U.readAll(in), "UTF-8");
        } catch (IOException e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ вкладки

    private void initTabs() {
        tabViews = new View[]{findViewById(R.id.tabBrowser), findViewById(R.id.tabGifts),
                findViewById(R.id.tabStickers), findViewById(R.id.tabSettings)};
        navPills = new View[]{findViewById(R.id.navPill0), findViewById(R.id.navPill1),
                findViewById(R.id.navPill2), findViewById(R.id.navPill3)};
        navIcons = new ImageView[]{findViewById(R.id.navIcon0), findViewById(R.id.navIcon1),
                findViewById(R.id.navIcon2), findViewById(R.id.navIcon3)};
        navLabels = new TextView[]{findViewById(R.id.navLabel0), findViewById(R.id.navLabel1),
                findViewById(R.id.navLabel2), findViewById(R.id.navLabel3)};
        int[] ids = {R.id.nav0, R.id.nav1, R.id.nav2, R.id.nav3};
        for (int i = 0; i < ids.length; i++) {
            final int t = i;
            findViewById(ids[i]).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showTab(t);
                }
            });
        }
    }

    private void showTab(int t) {
        tab = t;
        for (int i = 0; i < tabViews.length; i++) {
            tabViews[i].setVisibility(i == t ? View.VISIBLE : View.GONE);
            boolean sel = i == t;
            navPills[i].setBackgroundResource(sel ? R.drawable.bg_nav_selected : 0);
            ImageViewCompat.setImageTintList(navIcons[i], ColorStateList.valueOf(
                    sel ? U.onPrimaryContainer(this) : U.variant(this)));
            navLabels[i].setTextColor(sel ? U.primary(this) : U.variant(this));
            navLabels[i].setTypeface(navLabels[i].getTypeface(), sel ? Typeface.BOLD : Typeface.NORMAL);
        }
        if (t == TAB_GIFTS) {
            refreshGifts();
        } else if (t == TAB_STICKERS) {
            refreshStickers();
        }
    }

    // ------------------------------------------------------------------ сайт

    private void initSite() {
        web = findViewById(R.id.web);
        pageProgress = findViewById(R.id.pageProgress);
        pageTitle = findViewById(R.id.pageTitle);
        statusLine = findViewById(R.id.statusLine);
        hintPill = findViewById(R.id.hintPill);
        swCatcher = findViewById(R.id.swCatcher);

        int[] btns = {R.id.btnBack, R.id.btnReload, R.id.btnHome};
        for (int b : btns) {
            ImageViewCompat.setImageTintList((ImageButton) findViewById(b),
                    ColorStateList.valueOf(U.onSurface(this)));
        }
        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (web.canGoBack()) {
                    web.goBack();
                }
            }
        });
        findViewById(R.id.btnReload).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                web.reload();
            }
        });
        findViewById(R.id.btnHome).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                web.loadUrl("https://ok.ru/");
            }
        });

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setUserAgentString(Fetcher.UA);
        s.setSupportMultipleWindows(false);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(catcher.bridge(), "KlassBridge");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
                if (!scheme.equals("http") && !scheme.equals("https")) {
                    Diag.log("Заблокирована ссылка " + scheme + ":");
                    return true;
                }
                if (isInsideHost(u.getHost())) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException ignored) {
                    // нечем открыть
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                pageUrl = url == null ? "" : url;
                okPage = isOkHost(Uri.parse(pageUrl).getHost());
                catcher.forget();
                pageProgress.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                injectCatcher();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageProgress.setVisibility(View.INVISIBLE);
                if (okPage && url != null) {
                    Prefs.setLastUrl(MainActivity.this, url);
                }
                injectCatcher();
                updateStatus();
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Diag.net(request.getUrl().toString());
                return null;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                if (request.isForMainFrame()) {
                    statusLine.setText("Не удалось открыть страницу — проверьте интернет");
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                pageProgress.setProgress(p);
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                pageTitle.setText(title == null || title.isEmpty() ? "Одноклассники" : title);
            }
        });

        swCatcher.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                if (!syncing) {
                    setCatcher(on);
                }
            }
        });
        applyCatcherState(false);
        web.loadUrl(Prefs.lastUrl(this));
    }

    private static boolean isOkHost(String h) {
        if (h == null) {
            return false;
        }
        h = h.toLowerCase(Locale.ROOT);
        return h.equals("ok.ru") || h.endsWith(".ok.ru") || h.equals("odnoklassniki.ru")
                || h.endsWith(".odnoklassniki.ru");
    }

    /** Куда можно переходить внутри приложения (входы через почту/VK и картинки сайта). */
    private static boolean isInsideHost(String h) {
        if (h == null) {
            return false;
        }
        h = h.toLowerCase(Locale.ROOT);
        if (isOkHost(h)) {
            return true;
        }
        String[] ok = {"mail.ru", "vk.com", "vk.ru", "mycdn.me", "okcdn.ru", "ok.me"};
        for (String d : ok) {
            if (h.equals(d) || h.endsWith("." + d)) {
                return true;
            }
        }
        return false;
    }

    private void injectCatcher() {
        if (Prefs.catcherOn(this) && okPage && !catcherJs.isEmpty()) {
            web.evaluateJavascript(catcherJs, null);
        }
    }

    private void setCatcher(boolean on) {
        Prefs.setCatcherOn(this, on);
        applyCatcherState(true);
        if (Prefs.keepBg(this)) {
            KlassService.start(this); // обновит текст в шторке
        }
    }

    /** Приводит выключатели, подсказку и страницу в соответствие с настройкой. */
    private void applyCatcherState(boolean touchPage) {
        boolean on = Prefs.catcherOn(this);
        syncing = true;
        if (swCatcher != null) {
            swCatcher.setChecked(on);
        }
        if (swCatcher2 != null) {
            swCatcher2.setChecked(on);
        }
        syncing = false;
        if (hintPill != null) {
            hintPill.setVisibility(on ? View.VISIBLE : View.GONE);
        }
        if (web != null) {
            if (on) {
                injectCatcher();
                web.evaluateJavascript("window.__klass&&window.__klass.setOn(true)", null);
            } else {
                web.evaluateJavascript("window.__klass&&window.__klass.setOn(false)", null);
            }
        }
        updateStatus();
    }

    private void updateStatus() {
        if (statusLine == null) {
            return;
        }
        boolean on = Prefs.catcherOn(this);
        if (!on) {
            statusLine.setText("Ловец выключен — сайт работает как обычно");
        } else if (!okPage) {
            statusLine.setText("Ловец включён — откройте страницу Одноклассников");
        } else {
            statusLine.setText("Ловец включён — подарки в рамке можно скачать");
        }
    }

    // --- Catcher.Host

    @Override
    public boolean onOkPage() {
        return okPage;
    }

    @Override
    public void js(final String script) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (web != null) {
                    web.evaluateJavascript(script, null);
                }
            }
        });
    }

    @Override
    public void working(final String msg) {
        main.post(new Runnable() {
            @Override
            public void run() {
                statusLine.setText(msg);
            }
        });
    }

    @Override
    public void failed(final String msg, final String url) {
        main.post(new Runnable() {
            @Override
            public void run() {
                statusLine.setText(msg);
                U.toastLong(MainActivity.this, msg);
                web.evaluateJavascript("window.__klass&&window.__klass.flash(" + org.json.JSONObject.quote(url)
                        + ",false,'ошибка')", null);
            }
        });
    }

    @Override
    public void saved(final GiftStore.Gift g, final boolean isNew, final String url) {
        String err = null;
        boolean exported = Gallery.exported(this, g.id);
        if (!exported) {
            if (Gallery.needsPermission(this)) {
                pendingExport.add(g);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        ActivityCompat.requestPermissions(MainActivity.this,
                                new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
                    }
                });
            } else {
                try {
                    Gallery.save(this, g.file);
                    Gallery.markExported(this, g.id);
                    exported = true;
                } catch (IOException | RuntimeException e) {
                    err = e.getMessage();
                    Diag.log("Галерея: " + err);
                }
            }
        }
        final boolean inGallery = exported;
        final String galleryErr = err;
        main.post(new Runnable() {
            @Override
            public void run() {
                String msg;
                if (!isNew) {
                    msg = "Этот подарок уже скачан";
                } else if (inGallery) {
                    msg = "Подарок сохранён в галерею (GIF)";
                } else if (galleryErr != null) {
                    msg = "Подарок сохранён в приложении, в галерею не вышло: " + galleryErr;
                } else {
                    msg = "Подарок сохранён в приложении";
                }
                statusLine.setText(msg);
                web.evaluateJavascript("window.__klass&&window.__klass.flash(" + org.json.JSONObject.quote(url)
                        + ",true,'скачано')", null);
                refreshGifts();
                Snackbar sb = Snackbar.make(root, msg, Snackbar.LENGTH_LONG)
                        .setAnchorView(findViewById(R.id.bottomBar))
                        .setAction("Стикер", new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                showTab(TAB_GIFTS);
                                openGift(g);
                            }
                        });
                sb.show();
            }
        });
    }

    // ------------------------------------------------------------------ подарки

    private void initGifts() {
        giftsGrid = findViewById(R.id.giftsGrid);
        giftsEmpty = findViewById(R.id.giftsEmpty);
        giftsCount = findViewById(R.id.giftsCount);
        giftAdapter = new GiftAdapter();
        giftsGrid.setLayoutManager(new GridLayoutManager(this, 3));
        giftsGrid.setAdapter(giftAdapter);
        findViewById(R.id.btnGoBrowser).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTab(TAB_SITE);
            }
        });
    }

    private void refreshGifts() {
        final List<GiftStore.Gift> list = GiftStore.list(this);
        giftAdapter.set(list);
        giftsEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        giftsGrid.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
        giftsCount.setText(list.isEmpty() ? "" : list.size() + " шт.");
    }

    private final class GiftAdapter extends RecyclerView.Adapter<GiftAdapter.VH> {
        private List<GiftStore.Gift> items = new ArrayList<>();

        void set(List<GiftStore.Gift> l) {
            items = l;
            notifyDataSetChanged();
        }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_gift, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(VH h, int position) {
            final GiftStore.Gift g = items.get(position);
            Thumbs.load(h.img, g.file, false);
            h.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openGift(g);
                }
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        final class VH extends RecyclerView.ViewHolder {
            final ImageView img;

            VH(View v) {
                super(v);
                img = v.findViewById(R.id.img);
            }
        }
    }

    private BottomSheetDialog sheet(View content) {
        BottomSheetDialog d = new BottomSheetDialog(this);
        d.setContentView(content);
        d.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        d.getBehavior().setSkipCollapsed(true);
        return d;
    }

    private void openGift(final GiftStore.Gift g) {
        View v = LayoutInflater.from(this).inflate(R.layout.sheet_gift, null);
        final BottomSheetDialog d = sheet(v);
        ImageView preview = v.findViewById(R.id.sheetPreview);
        Thumbs.showAnimated(preview, g.file, false);
        ((TextView) v.findViewById(R.id.sheetTitle)).setText(
                (g.file.length() / 1024) + " КБ · GIF");
        v.findViewById(R.id.btnMakeSticker).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View x) {
                d.dismiss();
                makeSticker(g);
            }
        });
        v.findViewById(R.id.btnShare).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View x) {
                shareGift(g);
            }
        });
        v.findViewById(R.id.btnDelete).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View x) {
                d.dismiss();
                new MaterialAlertDialogBuilder(MainActivity.this)
                        .setTitle("Удалить подарок?")
                        .setMessage("Он исчезнет из раздела «Подарки». Копия в галерее останется.")
                        .setNegativeButton("Отмена", null)
                        .setPositiveButton("Удалить", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface di, int w) {
                                GiftStore.delete(g);
                                refreshGifts();
                            }
                        }).show();
            }
        });
        U.show(this, d);
    }

    private void shareGift(GiftStore.Gift g) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", g.file);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("image/gif");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Отправить подарок"));
        } catch (RuntimeException e) {
            U.toast(this, "Не удалось отправить");
        }
    }

    // ------------------------------------------------------------------ стикеры

    private void initStickers() {
        stickersGrid = findViewById(R.id.stickersGrid);
        stickersEmpty = findViewById(R.id.stickersEmpty);
        packName = findViewById(R.id.packName);
        packStatus = findViewById(R.id.packStatus);
        packProgress = findViewById(R.id.packProgress);
        btnWa = findViewById(R.id.btnWa);
        btnWaBiz = findViewById(R.id.btnWaBiz);
        stickerAdapter = new StickerAdapter();
        stickersGrid.setLayoutManager(new GridLayoutManager(this, 3));
        stickersGrid.setAdapter(stickerAdapter);
        findViewById(R.id.btnPacks).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPacksMenu();
            }
        });
        btnWa.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean wa = WhatsAppHelper.isInstalled(MainActivity.this, WhatsAppHelper.PKG);
                sendToWhatsApp(wa ? WhatsAppHelper.PKG : WhatsAppHelper.PKG_BUSINESS);
            }
        });
        btnWaBiz.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendToWhatsApp(WhatsAppHelper.PKG_BUSINESS);
            }
        });
    }

    private Pack activePack() {
        String id = Prefs.packId(this);
        Pack p = id == null ? null : packs.load(id);
        if (p == null) {
            List<Pack> all = packs.loadAll();
            if (!all.isEmpty()) {
                p = all.get(0);
                Prefs.setPackId(this, p.id);
            }
        }
        return p;
    }

    private void refreshStickers() {
        Pack p = activePack();
        boolean hasWa = WhatsAppHelper.isInstalled(this, WhatsAppHelper.PKG);
        boolean hasBiz = WhatsAppHelper.isInstalled(this, WhatsAppHelper.PKG_BUSINESS);
        btnWaBiz.setVisibility(hasWa && hasBiz ? View.VISIBLE : View.GONE);
        if (p == null) {
            packName.setText("Набор ещё не создан");
            packStatus.setText("Сделайте первый стикер из подарка — набор создастся сам.");
            packProgress.setProgress(0);
            stickerAdapter.set(p, new ArrayList<Pack.Item>());
            stickersEmpty.setVisibility(View.VISIBLE);
            btnWa.setEnabled(false);
            btnWaBiz.setEnabled(false);
            return;
        }
        int n = p.stickers.size();
        packName.setText(p.name);
        packProgress.setProgress(Math.min(n, Pack.MIN));
        boolean ready = n >= Pack.MIN;
        if (ready) {
            packStatus.setText("Стикеров: " + n + " из " + Pack.MAX + ". Набор готов — добавьте его в WhatsApp.");
        } else {
            packStatus.setText("Стикеров: " + n + ". Для WhatsApp нужно минимум " + Pack.MIN
                    + " — сделайте ещё " + (Pack.MIN - n) + ".");
        }
        stickerAdapter.set(p, new ArrayList<>(p.stickers));
        stickersEmpty.setVisibility(n == 0 ? View.VISIBLE : View.GONE);
        btnWa.setEnabled(ready && (hasWa || hasBiz));
        btnWaBiz.setEnabled(ready);
        btnWa.setText(hasWa || hasBiz ? "Добавить в WhatsApp" : "WhatsApp не установлен");
    }

    private final class StickerAdapter extends RecyclerView.Adapter<StickerAdapter.VH> {
        private Pack pack;
        private List<Pack.Item> items = new ArrayList<>();

        void set(Pack p, List<Pack.Item> l) {
            pack = p;
            items = l;
            notifyDataSetChanged();
        }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_gift, parent, false));
        }

        @Override
        public void onBindViewHolder(VH h, int position) {
            final Pack.Item it = items.get(position);
            final File f = packs.file(pack.id, it.file);
            if (f != null) {
                Thumbs.load(h.img, f, true);
            }
            h.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (f != null) {
                        openSticker(pack, it, f);
                    }
                }
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        final class VH extends RecyclerView.ViewHolder {
            final ImageView img;

            VH(View v) {
                super(v);
                img = v.findViewById(R.id.img);
            }
        }
    }

    private void openSticker(final Pack p, final Pack.Item it, File f) {
        View v = LayoutInflater.from(this).inflate(R.layout.sheet_gift, null);
        final BottomSheetDialog d = sheet(v);
        Thumbs.showAnimated((ImageView) v.findViewById(R.id.sheetPreview), f, true);
        ((TextView) v.findViewById(R.id.sheetTitle)).setText("Анимированный стикер · " + (f.length() / 1024) + " КБ");
        v.findViewById(R.id.btnMakeSticker).setVisibility(View.GONE);
        v.findViewById(R.id.btnShare).setVisibility(View.GONE);
        MaterialButton del = v.findViewById(R.id.btnDelete);
        del.setText("Удалить стикер");
        del.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View x) {
                d.dismiss();
                try {
                    packs.removeSticker(p.id, it.file);
                } catch (IOException e) {
                    U.toast(MainActivity.this, "Не удалось удалить");
                }
                refreshStickers();
            }
        });
        U.show(this, d);
    }

    /** Делает из подарка анимированный стикер и кладёт в текущий набор. */
    private void makeSticker(final GiftStore.Gift g) {
        View pv = LayoutInflater.from(this).inflate(R.layout.dialog_progress, null);
        final androidx.appcompat.app.AlertDialog dlg = new MaterialAlertDialogBuilder(this)
                .setView(pv).setCancelable(false).create();
        U.show(this, dlg);
        io.execute(new Runnable() {
            @Override
            public void run() {
                String err = null;
                int count = 0;
                try {
                    byte[] webp = StickerMaker.make(U.readFile(g.file));
                    Pack p = activePack();
                    if (p == null || p.stickers.size() >= Pack.MAX) {
                        String name = p == null ? "Подарки Rin-klass"
                                : "Подарки Rin-klass " + (packs.loadAll().size() + 1);
                        p = packs.create(name, Prefs.publisher(MainActivity.this));
                        Prefs.setPackId(MainActivity.this, p.id);
                    }
                    p = packs.addSticker(p.id, webp, EMOJI);
                    count = p.stickers.size();
                } catch (IOException e) {
                    err = e.getMessage() == null ? "Не получилось сделать стикер" : e.getMessage();
                } catch (OutOfMemoryError e) {
                    err = "Подарок слишком большой для телефона";
                } catch (RuntimeException e) {
                    err = "Ошибка: " + e;
                }
                final String fe = err;
                final int fc = count;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        dlg.dismiss();
                        if (fe != null) {
                            Diag.log("Стикер: " + fe);
                            new MaterialAlertDialogBuilder(MainActivity.this)
                                    .setTitle("Стикер не получился")
                                    .setMessage(fe)
                                    .setPositiveButton("Понятно", null).show();
                            return;
                        }
                        refreshStickers();
                        String msg = fc >= Pack.MIN
                                ? "Стикер добавлен (" + fc + "). Набор готов для WhatsApp"
                                : "Стикер добавлен (" + fc + " из " + Pack.MIN + " для WhatsApp)";
                        Snackbar.make(root, msg, Snackbar.LENGTH_LONG)
                                .setAnchorView(findViewById(R.id.bottomBar))
                                .setAction("К стикерам", new View.OnClickListener() {
                                    @Override
                                    public void onClick(View v) {
                                        showTab(TAB_STICKERS);
                                    }
                                }).show();
                    }
                });
            }
        });
    }

    private void sendToWhatsApp(String pkg) {
        Pack p = activePack();
        if (p == null || p.stickers.size() < Pack.MIN) {
            U.toast(this, "Нужно минимум " + Pack.MIN + " стикера");
            return;
        }
        try {
            startActivityForResult(WhatsAppHelper.buildIntent(this, p, pkg), REQ_WA);
        } catch (ActivityNotFoundException e) {
            U.toastLong(this, "WhatsApp не принял набор. Обновите WhatsApp и попробуйте ещё раз.");
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == REQ_WA && result != Activity.RESULT_OK && data != null) {
            String err = data.getStringExtra("validation_error");
            if (err != null && !err.isEmpty()) {
                Diag.log("WhatsApp: " + err);
                new MaterialAlertDialogBuilder(this)
                        .setTitle("WhatsApp не принял набор")
                        .setMessage(err)
                        .setPositiveButton("Понятно", null).show();
            }
        }
    }

    private void showPacksMenu() {
        final List<Pack> all = packs.loadAll();
        final List<String> labels = new ArrayList<>();
        for (Pack p : all) {
            boolean cur = activePack() != null && activePack().id.equals(p.id);
            labels.add((cur ? "✓ " : "") + p.name + " (" + p.stickers.size() + ")");
        }
        final int iNew = labels.size();
        labels.add("＋ Новый набор");
        final boolean has = !all.isEmpty();
        if (has) {
            labels.add("Переименовать текущий");
            labels.add("Удалить текущий");
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("Наборы стикеров")
                .setItems(labels.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (which < iNew) {
                            Prefs.setPackId(MainActivity.this, all.get(which).id);
                            refreshStickers();
                        } else if (which == iNew) {
                            askText("Новый набор", "Подарки " + (all.size() + 1), new TextCb() {
                                @Override
                                public void ok(String text) {
                                    try {
                                        Pack p = packs.create(text, Prefs.publisher(MainActivity.this));
                                        Prefs.setPackId(MainActivity.this, p.id);
                                        refreshStickers();
                                    } catch (IOException e) {
                                        U.toast(MainActivity.this, "Не удалось создать набор");
                                    }
                                }
                            });
                        } else if (which == iNew + 1) {
                            final Pack cur = activePack();
                            if (cur != null) {
                                askText("Название набора", cur.name, new TextCb() {
                                    @Override
                                    public void ok(String text) {
                                        try {
                                            packs.rename(cur.id, text, cur.publisher);
                                            refreshStickers();
                                        } catch (IOException e) {
                                            U.toast(MainActivity.this, "Не удалось переименовать");
                                        }
                                    }
                                });
                            }
                        } else {
                            final Pack cur = activePack();
                            if (cur != null) {
                                new MaterialAlertDialogBuilder(MainActivity.this)
                                        .setTitle("Удалить набор «" + cur.name + "»?")
                                        .setMessage("Стикеры из него будут удалены.")
                                        .setNegativeButton("Отмена", null)
                                        .setPositiveButton("Удалить", new android.content.DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(android.content.DialogInterface di, int w) {
                                                packs.delete(cur.id);
                                                Prefs.setPackId(MainActivity.this, null);
                                                refreshStickers();
                                            }
                                        }).show();
                            }
                        }
                    }
                }).show();
    }

    private interface TextCb {
        void ok(String text);
    }

    private void askText(String title, String initial, final TextCb cb) {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setText(initial);
        et.setSelectAllOnFocus(true);
        et.setSingleLine(true);
        FrameLayout box = new FrameLayout(this);
        int m = U.dp(this, 22);
        box.setPadding(m, U.dp(this, 8), m, 0);
        box.addView(et, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        androidx.appcompat.app.AlertDialog d = new MaterialAlertDialogBuilder(this)
                .setTitle(title).setView(box)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Готово", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface di, int w) {
                        String t = et.getText().toString().trim();
                        if (!t.isEmpty()) {
                            cb.ok(t.length() > 60 ? t.substring(0, 60) : t);
                        }
                    }
                }).create();
        U.show(this, d);
    }

    // ------------------------------------------------------------------ настройки

    private void initSettings() {
        swCatcher2 = findViewById(R.id.swCatcher2);
        themeRow = findViewById(R.id.themeRow);
        themeValue = findViewById(R.id.themeValue);
        fontSample = findViewById(R.id.fontSample);
        fontValue = findViewById(R.id.fontValue);
        MaterialSwitch swBg = findViewById(R.id.swBackground);
        swCatcher2.setChecked(Prefs.catcherOn(this));
        swCatcher2.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                if (!syncing) {
                    setCatcher(on);
                }
            }
        });
        swBg.setChecked(Prefs.keepBg(this));
        swBg.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                Prefs.setKeepBg(MainActivity.this, on);
                if (on) {
                    askNotifications();
                    KlassService.start(MainActivity.this);
                } else {
                    KlassService.stop(MainActivity.this);
                }
            }
        });

        buildThemeRow();
        updateFontLabels();
        findViewById(R.id.btnFont).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showFontSheet();
            }
        });
        findViewById(R.id.btnPublisher).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askText("Автор набора стикеров", Prefs.publisher(MainActivity.this), new TextCb() {
                    @Override
                    public void ok(String text) {
                        Prefs.setPublisher(MainActivity.this, text);
                        Pack p = activePack();
                        if (p != null) {
                            try {
                                packs.rename(p.id, p.name, text);
                            } catch (IOException ignored) {
                                // автор применится к новым наборам
                            }
                        }
                    }
                });
            }
        });
        findViewById(R.id.btnDiag).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDiag();
            }
        });
        TextView about = findViewById(R.id.aboutText);
        String ver = "1.0";
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            ver = pi.versionName + " · сборка " + (Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode);
        } catch (PackageManager.NameNotFoundException ignored) {
            // версия не критична
        }
        about.setText("Rin-klass " + ver);
    }

    private void buildThemeRow() {
        themeRow.removeAllViews();
        int cur = ThemeManager.index(this);
        themeValue.setText(ThemeManager.name(cur));
        int size = U.dp(this, 46);
        int gap = U.dp(this, 5);
        View selected = null;
        for (int i = 0; i < ThemeManager.count(); i++) {
            final int idx = i;
            FrameLayout cell = new FrameLayout(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size + 2 * gap, size + 2 * gap);
            cell.setLayoutParams(lp);
            View dot = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(ThemeManager.primary(i));
            g.setStroke(U.dp(this, i == cur ? 3 : 1), i == cur ? U.onSurface(this) : U.outlineVariant(this));
            dot.setBackground(g);
            FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(size, size);
            dl.gravity = Gravity.CENTER;
            cell.addView(dot, dl);
            if (i == cur) {
                TextView ck = new TextView(this);
                ck.setText("✓");
                ck.setTextColor(Color.WHITE);
                ck.setShadowLayer(3f, 0, 0, Color.BLACK);
                ck.setTextSize(18);
                ck.setTypeface(Typeface.DEFAULT_BOLD);
                FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cl.gravity = Gravity.CENTER;
                cell.addView(ck, cl);
                selected = cell;
            }
            cell.setContentDescription(ThemeManager.name(i));
            cell.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (idx != ThemeManager.index(MainActivity.this)) {
                        Prefs.setTheme(MainActivity.this, idx);
                        recreate();
                    }
                }
            });
            themeRow.addView(cell);
        }
        final View sel = selected;
        if (sel != null) {
            final HorizontalScrollView hs = (HorizontalScrollView) themeRow.getParent();
            hs.post(new Runnable() {
                @Override
                public void run() {
                    hs.scrollTo(Math.max(0, sel.getLeft() - hs.getWidth() / 2 + sel.getWidth() / 2), 0);
                }
            });
        }
    }

    private void updateFontLabels() {
        int sel = FontManager.selected(this);
        fontValue.setText(FontManager.label(this, sel));
        Typeface tf = FontManager.get(this, sel);
        fontSample.setTypeface(tf == null ? Typeface.DEFAULT : tf);
    }

    private void showFontSheet() {
        View v = LayoutInflater.from(this).inflate(R.layout.sheet_list, null);
        final BottomSheetDialog d = sheet(v);
        ((TextView) v.findViewById(R.id.sheetListTitle)).setText("Шрифт приложения");
        RecyclerView rv = v.findViewById(R.id.sheetList);
        rv.setLayoutManager(new LinearLayoutManager(this));
        final int sel = FontManager.selected(this);
        rv.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
                View row = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_font, parent, false);
                return new RecyclerView.ViewHolder(row) {
                };
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder h, final int position) {
                TextView t = (TextView) h.itemView;
                t.setText((position == sel ? "✓  " : "") + FontManager.label(MainActivity.this, position));
                Typeface tf = FontManager.get(MainActivity.this, position);
                t.setTypeface(tf == null ? Typeface.DEFAULT : tf);
                t.setTextColor(position == sel ? U.primary(MainActivity.this) : U.onSurface(MainActivity.this));
                t.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View x) {
                        d.dismiss();
                        Prefs.setFont(MainActivity.this, FontManager.file(MainActivity.this, position));
                        if (FontManager.get(MainActivity.this, position) == null) {
                            recreate();
                        } else {
                            UiFont.apply(MainActivity.this, root);
                            updateFontLabels();
                        }
                    }
                });
            }

            @Override
            public int getItemCount() {
                return FontManager.count(MainActivity.this);
            }
        });
        d.show();
        rv.scrollToPosition(Math.max(0, sel - 2));
    }

    private void showDiag() {
        final TextView tv = new TextView(this);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(11);
        tv.setTextIsSelectable(true);
        int m = U.dp(this, 16);
        tv.setPadding(m, m, m, m);
        final ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        final Runnable fill = new Runnable() {
            @Override
            public void run() {
                tv.setText("Страница: " + pageUrl + "\nСтраница Одноклассников: " + okPage
                        + "\nЛовец: " + (Prefs.catcherOn(MainActivity.this) ? "включён" : "выключен")
                        + "\nAndroid " + Build.VERSION.RELEASE + ", " + Build.MODEL + "\n\n" + Diag.dump());
            }
        };
        fill.run();
        androidx.appcompat.app.AlertDialog d = new MaterialAlertDialogBuilder(this)
                .setTitle("Диагностика")
                .setView(sv)
                .setNegativeButton("Закрыть", null)
                .setNeutralButton("Копировать", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface di, int w) {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("Rin-klass", tv.getText()));
                            U.toast(MainActivity.this, "Скопировано");
                        }
                    }
                })
                .setPositiveButton("Обновить", null)
                .create();
        d.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            @Override
            public void onShow(android.content.DialogInterface di) {
                ((androidx.appcompat.app.AlertDialog) di).getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                        .setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                fill.run();
                            }
                        });
            }
        });
        U.show(this, d);
    }

    // ------------------------------------------------------------------ разрешения

    private void askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && Prefs.keepBg(this) && !Prefs.askedNotifications(this)
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            Prefs.setAskedNotifications(this);
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_STORAGE) {
            final boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            final List<GiftStore.Gift> todo = new ArrayList<>(pendingExport);
            pendingExport.clear();
            if (!ok) {
                U.toastLong(this, "Без разрешения подарки хранятся только в приложении («Подарки»)");
                return;
            }
            io.execute(new Runnable() {
                @Override
                public void run() {
                    int n = 0;
                    for (GiftStore.Gift g : todo) {
                        try {
                            Gallery.save(MainActivity.this, g.file);
                            Gallery.markExported(MainActivity.this, g.id);
                            n++;
                        } catch (IOException | RuntimeException e) {
                            Diag.log("Галерея: " + e.getMessage());
                        }
                    }
                    final int saved = n;
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (saved > 0) {
                                U.toast(MainActivity.this, "Сохранено в галерею: " + saved);
                            }
                        }
                    });
                }
            });
        }
    }
}
