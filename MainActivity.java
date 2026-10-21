package com.eokers.pixelartstudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import android.content.SharedPreferences;

public class MainActivity extends Activity {
    private static final int REQ_OPEN_IMAGE = 101;
    private static final int REQ_SAVE_PNG = 102;
    private static final int MAX_HISTORY = 24;

    private int gridSize = 32;
    private int[] pixels = new int[gridSize * gridSize];
    private Bitmap referenceBitmap;
    private boolean showReference = true;
    private boolean showGrid = true;
    private float referenceOpacity = 0.35f;
    private int currentColor = Color.rgb(30, 30, 35);
    private int secondaryColor = Color.WHITE;
    private String currentTool = "Карандаш";
    private boolean exportWithReference = false;
    private boolean numberMode = false;
    private int[] numberMap = new int[0];
    private int[] numberColors = new int[0];
    private long lastWrongColorToast = 0L;

    private final List<Integer> palette = new ArrayList<>();
    private final ArrayDeque<int[]> undoStack = new ArrayDeque<>();
    private final ArrayDeque<int[]> redoStack = new ArrayDeque<>();

    private LinearLayout root;
    private LinearLayout paletteRow;
    private TextView selectedColorView;
    private TextView referenceButton;
    private TextView statusLabel;
    private PixelCanvas pixelCanvas;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(21, 23, 28));
        getWindow().setNavigationBarColor(Color.rgb(21, 23, 28));
        seedPalette();
        buildUi();
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void seedPalette() {
        int[] colors = {
                0xFF1E1E23, 0xFFFFFFFF, 0xFFEF4444, 0xFFF97316,
                0xFFFACC15, 0xFF22C55E, 0xFF14B8A6, 0xFF3B82F6,
                0xFF8B5CF6, 0xFFEC4899, 0xFF8B5E3C, 0xFF9CA3AF,
                0xFF374151, 0xFFFFC0CB, 0xFFB7E4C7, 0xFFBDE0FE
        };
        for (int color : colors) palette.add(color);
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(29, 32, 39));
        root.setPadding(dp(12), dp(8), dp(12), dp(8));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = new TextView(this);
        title.setText("PIXEL ART STUDIO");
        title.setTextColor(Color.WHITE);
        title.setTextSize(19);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView sizeLabel = new TextView(this);
        sizeLabel.setText(gridSize + " × " + gridSize);
        sizeLabel.setTextColor(Color.rgb(255, 159, 74));
        sizeLabel.setTextSize(13);
        sizeLabel.setPadding(dp(8), dp(5), dp(8), dp(5));
        sizeLabel.setBackground(roundBackground(0xFF30343D, 0xFF414651, dp(9)));
        sizeLabel.setOnClickListener(v -> chooseGridSize());
        header.addView(sizeLabel);
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        statusLabel = new TextView(this);
        statusLabel.setTextColor(0xFFACB2BE);
        statusLabel.setTextSize(11);
        statusLabel.setMaxLines(2);
        statusLabel.setPadding(0, 0, 0, dp(7));
        refreshStatus();
        root.addView(statusLabel);

        root.addView(toolbar(new String[]{"Карандаш", "Ластик", "Заливка", "Пипетка"}, tool -> setTool(tool)));

        LinearLayout creativeActions = new LinearLayout(this);
        creativeActions.setOrientation(LinearLayout.HORIZONTAL);
        addChipTo(creativeActions, "✦ ИИ-арт", () -> showAiDialog(), true);
        addChipTo(creativeActions, "№ Раскраска", () -> convertToNumberColoring(), false);
        addChipTo(creativeActions, numberMode ? "Обычный режим" : "Новый лист", () -> {
            if (numberMode) leaveNumberColoring(); else createBlankCanvas();
        }, false);
        root.addView(horizontal(creativeActions));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        addChipTo(actions, "Открыть", () -> openReference(), false);
        addChipTo(actions, "PNG", () -> chooseExportMode(), false);
        referenceButton = makeChip("Реф. 35%", () -> toggleReference(), showReference);
        actions.addView(referenceButton, chipParams());
        addChipTo(actions, "Прозр.", () -> chooseReferenceOpacity(), false);
        addChipTo(actions, "Сетка", () -> toggleGrid(), showGrid);
        root.addView(horizontal(actions));

        pixelCanvas = new PixelCanvas();
        LinearLayout.LayoutParams canvasParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        canvasParams.topMargin = dp(4);
        canvasParams.bottomMargin = dp(4);
        root.addView(pixelCanvas, canvasParams);

        LinearLayout history = new LinearLayout(this);
        history.setOrientation(LinearLayout.HORIZONTAL);
        addChipTo(history, "↶ Отмена", () -> undo(), false);
        addChipTo(history, "↷ Повтор", () -> redo(), false);
        addChipTo(history, "Очистить", () -> confirmClear(), false);
        addChipTo(history, "Размер", () -> chooseGridSize(), false);
        root.addView(horizontal(history));

        LinearLayout colorActions = new LinearLayout(this);
        colorActions.setOrientation(LinearLayout.HORIZONTAL);
        selectedColorView = makeChip("Цвет", () -> showColorDialog(), false);
        selectedColorView.setCompoundDrawablePadding(dp(5));
        colorActions.addView(selectedColorView, chipParams());
        addChipTo(colorActions, "Смешать", () -> showMixDialog(), false);
        addChipTo(colorActions, "Оттенки", () -> addShades(), false);
        root.addView(horizontal(colorActions));

        HorizontalScrollView paletteScroll = new HorizontalScrollView(this);
        paletteScroll.setHorizontalScrollBarEnabled(false);
        paletteRow = new LinearLayout(this);
        paletteRow.setOrientation(LinearLayout.HORIZONTAL);
        paletteRow.setGravity(Gravity.CENTER_VERTICAL);
        paletteRow.setPadding(dp(2), dp(7), dp(2), dp(5));
        paletteScroll.addView(paletteRow);
        root.addView(paletteScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

        TextView footer = new TextView(this);
        footer.setText("PNG сохраняется с прозрачным фоном, если не добавлять референс");
        footer.setTextColor(0xFF838B99);
        footer.setTextSize(10);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(2), 0, dp(2));
        root.addView(footer);

        setContentView(root);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int topInset = insets.getSystemWindowInsetTop();
            int bottomInset = insets.getSystemWindowInsetBottom();
            view.setPadding(dp(12), dp(8) + topInset, dp(12), dp(8) + bottomInset);
            return insets;
        });
        refreshPalette();
        refreshColorButton();
    }

    private HorizontalScrollView toolbar(String[] labels, java.util.function.Consumer<String> callback) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (String label : labels) {
            TextView chip = makeChip(label, () -> callback.accept(label), label.equals(currentTool));
            row.addView(chip, chipParams());
        }
        return horizontal(row);
    }

    private HorizontalScrollView horizontal(LinearLayout row) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(false);
        scroll.addView(row, new HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private LinearLayout.LayoutParams chipParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
        p.rightMargin = dp(6);
        p.bottomMargin = dp(4);
        return p;
    }

    private TextView makeChip(String text, Runnable action, boolean active) {
        TextView chip = new TextView(this);
        chip.setText(text);
        chip.setTextColor(Color.WHITE);
        chip.setTextSize(12);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(12), dp(7), dp(12), dp(7));
        chip.setMinWidth(dp(52));
        chip.setBackground(roundBackground(active ? 0xFFFF8738 : 0xFF30343D,
                active ? 0xFFFFAA70 : 0xFF454A55, dp(9)));
        chip.setOnClickListener(v -> action.run());
        return chip;
    }

    private void addChipTo(LinearLayout row, String text, Runnable action, boolean active) {
        row.addView(makeChip(text, action, active), chipParams());
    }

    private GradientDrawable roundBackground(int fill, int stroke, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radius);
        d.setStroke(dp(1), stroke);
        return d;
    }

    private void setTool(String tool) {
        currentTool = tool;
        // Rebuild only tool selector so active state is visible.
        buildUi();
        pixelCanvas.invalidate();
    }

    private void refreshStatus() {
        if (statusLabel == null) return;
        if (numberMode && numberMap.length > 0) {
            int done = 0;
            for (int color : pixels) if (Color.alpha(color) > 0) done++;
            int total = numberMap.length;
            int percent = total == 0 ? 0 : Math.round(done * 100f / total);
            statusLabel.setText("РАСКРАСКА ПО НОМЕРАМ · " + percent + "% готово · " + done + "/" + total + " клеток");
        } else {
            statusLabel.setText("Рисуй по пикселям · загружай референсы · сохраняй PNG");
        }
    }

    private void refreshColorButton() {
        if (selectedColorView == null) return;
        selectedColorView.setText("Цвет  " + String.format(Locale.US, "#%06X", currentColor & 0xFFFFFF));
        selectedColorView.setBackground(roundBackground(0xFF30343D, currentColor, dp(9)));
    }

    private void refreshPalette() {
        if (paletteRow == null) return;
        paletteRow.removeAllViews();
        if (numberMode && numberColors.length > 0) {
            for (int i = 0; i < numberColors.length; i++) {
                final int color = numberColors[i];
                TextView swatch = new TextView(this);
                swatch.setText(String.valueOf(i + 1));
                swatch.setTextColor(contrastColor(color));
                swatch.setTextSize(13);
                swatch.setTypeface(null, android.graphics.Typeface.BOLD);
                swatch.setGravity(Gravity.CENTER);
                swatch.setBackground(roundBackground(color, color == currentColor ? 0xFFFF8738 : 0xFF777D89,
                        dp(color == currentColor ? 3 : 1)));
                swatch.setContentDescription("Цвет номер " + (i + 1));
                swatch.setOnClickListener(v -> { currentColor = color; refreshPalette(); refreshColorButton(); });
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(38), dp(38));
                p.rightMargin = dp(7);
                paletteRow.addView(swatch, p);
            }
            return;
        }
        for (int color : palette) {
            View swatch = new View(this);
            GradientDrawable d = new GradientDrawable();
            d.setColor(color);
            d.setCornerRadius(dp(8));
            d.setStroke(dp(color == currentColor ? 3 : 1), color == currentColor ? 0xFFFF8738 : 0xFF777D89);
            swatch.setBackground(d);
            swatch.setContentDescription(String.format(Locale.US, "Цвет #%06X", color & 0xFFFFFF));
            swatch.setOnClickListener(v -> { currentColor = color; refreshPalette(); refreshColorButton(); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(38), dp(38));
            p.rightMargin = dp(7);
            paletteRow.addView(swatch, p);
        }
        View plus = makeChip("+", () -> showColorDialog(), false);
        paletteRow.addView(plus, new LinearLayout.LayoutParams(dp(42), dp(38)));
    }

    private int contrastColor(int color) {
        double luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color));
        return luminance > 150 ? Color.BLACK : Color.WHITE;
    }

    private void addPaletteColor(int color) {
        if (numberMode) leaveNumberColoring();
        if (!palette.contains(color)) palette.add(0, color);
        while (palette.size() > 40) palette.remove(palette.size() - 1);
        currentColor = color;
        refreshPalette();
        refreshColorButton();
    }

    private void showColorDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(10), dp(20), dp(4));
        View preview = new View(this);
        preview.setBackground(roundBackground(currentColor, Color.LTGRAY, dp(10)));
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        previewParams.bottomMargin = dp(8);
        layout.addView(preview, previewParams);
        SeekBar[] bars = new SeekBar[3];
        String[] names = {"Красный", "Зелёный", "Синий"};
        int[] initial = {Color.red(currentColor), Color.green(currentColor), Color.blue(currentColor)};
        for (int i = 0; i < 3; i++) {
            TextView label = new TextView(this);
            label.setText(names[i] + ": " + initial[i]);
            label.setTextColor(Color.DKGRAY);
            layout.addView(label);
            SeekBar bar = new SeekBar(this);
            bar.setMax(255);
            bar.setProgress(initial[i]);
            bars[i] = bar;
            final int idx = i;
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    label.setText(names[idx] + ": " + progress);
                    preview.setBackground(roundBackground(Color.rgb(bars[0].getProgress(), bars[1].getProgress(), bars[2].getProgress()), Color.LTGRAY, dp(10)));
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) { }
                @Override public void onStopTrackingTouch(SeekBar seekBar) { }
            });
            layout.addView(bar);
        }
        new AlertDialog.Builder(this)
                .setTitle("Выбор цвета")
                .setView(layout)
                .setNegativeButton("Отмена", null)
                .setNeutralButton("В палитру", (dialog, which) -> addPaletteColor(Color.rgb(bars[0].getProgress(), bars[1].getProgress(), bars[2].getProgress())))
                .setPositiveButton("Выбрать", (dialog, which) -> {
                    currentColor = Color.rgb(bars[0].getProgress(), bars[1].getProgress(), bars[2].getProgress());
                    refreshPalette(); refreshColorButton();
                }).show();
    }

    private void showMixDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(8), dp(20), dp(4));
        TextView explanation = new TextView(this);
        explanation.setText("A — выбранный цвет. B — дополнительный цвет. Двигай ползунок, чтобы смешать оттенки.");
        explanation.setTextColor(Color.DKGRAY);
        layout.addView(explanation);
        LinearLayout swatches = new LinearLayout(this);
        swatches.setGravity(Gravity.CENTER_VERTICAL);
        View aView = new View(this);
        aView.setBackground(roundBackground(currentColor, Color.LTGRAY, dp(8)));
        swatches.addView(aView, new LinearLayout.LayoutParams(0, dp(42), 1f));
        TextView changeB = new TextView(this);
        changeB.setText("  Цвет B: выбрать  ");
        changeB.setTextColor(0xFF202020);
        changeB.setGravity(Gravity.CENTER);
        changeB.setOnClickListener(v -> choosePaletteColor(color -> {
            secondaryColor = color;
            changeB.setBackground(roundBackground(color, Color.GRAY, dp(8)));
        }));
        swatches.addView(changeB, new LinearLayout.LayoutParams(0, dp(42), 1f));
        layout.addView(swatches);
        View resultPreview = new View(this);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        rp.topMargin = dp(10);
        layout.addView(resultPreview, rp);
        TextView amountLabel = new TextView(this);
        amountLabel.setText("Доля цвета B: 50%");
        amountLabel.setTextColor(Color.DKGRAY);
        layout.addView(amountLabel);
        SeekBar amount = new SeekBar(this);
        amount.setMax(100);
        amount.setProgress(50);
        layout.addView(amount);
        final int[] mixed = {blend(currentColor, secondaryColor, 0.5f)};
        resultPreview.setBackground(roundBackground(mixed[0], Color.LTGRAY, dp(10)));
        amount.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                amountLabel.setText("Доля цвета B: " + progress + "%");
                mixed[0] = blend(currentColor, secondaryColor, progress / 100f);
                resultPreview.setBackground(roundBackground(mixed[0], Color.LTGRAY, dp(10)));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        new AlertDialog.Builder(this).setTitle("Смешивание оттенков").setView(layout)
                .setNegativeButton("Отмена", null)
                .setNeutralButton("В палитру", (d, w) -> addPaletteColor(mixed[0]))
                .setPositiveButton("Выбрать", (d, w) -> { currentColor = mixed[0]; refreshPalette(); refreshColorButton(); })
                .show();
    }

    private int blend(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        return Color.rgb(
                Math.round(Color.red(a) * (1 - t) + Color.red(b) * t),
                Math.round(Color.green(a) * (1 - t) + Color.green(b) * t),
                Math.round(Color.blue(a) * (1 - t) + Color.blue(b) * t));
    }

    private interface ColorCallback { void onColor(int color); }

    private void choosePaletteColor(ColorCallback callback) {
        String[] labels = new String[palette.size()];
        for (int i = 0; i < palette.size(); i++) labels[i] = String.format(Locale.US, "●  #%06X", palette.get(i) & 0xFFFFFF);
        new AlertDialog.Builder(this).setTitle("Выбери цвет из палитры").setItems(labels, (d, which) -> callback.onColor(palette.get(which))).show();
    }

    private void addShades() {
        int r = Color.red(currentColor), g = Color.green(currentColor), b = Color.blue(currentColor);
        int[] factors = {45, 70, 85, 115, 135, 160};
        for (int factor : factors) {
            int nr = Math.min(255, r * factor / 100);
            int ng = Math.min(255, g * factor / 100);
            int nb = Math.min(255, b * factor / 100);
            int color = Color.rgb(nr, ng, nb);
            if (!palette.contains(color)) palette.add(0, color);
        }
        while (palette.size() > 40) palette.remove(palette.size() - 1);
        refreshPalette();
        toast("Оттенки добавлены в палитру");
    }

    private void createBlankCanvas() {
        new AlertDialog.Builder(this).setTitle("Новый пиксельный холст")
                .setMessage("Создать чистый холст? Текущий рисунок будет заменён.")
                .setNegativeButton("Назад", null)
                .setPositiveButton("Создать", (d, w) -> {
                    pushUndo();
                    Arrays.fill(pixels, Color.TRANSPARENT);
                    numberMode = false; numberMap = new int[0]; numberColors = new int[0];
                    if (referenceBitmap != null && !referenceBitmap.isRecycled()) referenceBitmap.recycle();
                    referenceBitmap = null;
                    showReference = false;
                    refreshPalette(); buildUi();
                    toast("Новый холст готов");
                }).show();
    }

    private void leaveNumberColoring() {
        numberMode = false;
        numberMap = new int[0];
        numberColors = new int[0];
        currentTool = "Карандаш";
        showReference = false;
        refreshPalette();
        buildUi();
        toast("Обычный режим включён");
    }

    private void showAiDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(18), dp(8), dp(18), dp(4));
        TextView info = new TextView(this);
        info.setText("Опиши персонажа или предмет. ИИ создаст картинку, а приложение превратит её в пиксели.\n\nНужен API-ключ OpenAI. Генерация может стоить денег; подписка ChatGPT не включает API-кредиты.");
        info.setTextColor(0xFF343943);
        info.setTextSize(13);
        layout.addView(info);
        EditText prompt = new EditText(this);
        prompt.setHint("Например: маленький рыцарь с огненным мечом");
        prompt.setText("cute pixel-art fox adventurer, limited color palette");
        prompt.setMinLines(2);
        prompt.setMaxLines(4);
        prompt.setGravity(Gravity.TOP | Gravity.START);
        prompt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        LinearLayout.LayoutParams promptParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        promptParams.topMargin = dp(10);
        layout.addView(prompt, promptParams);
        EditText key = new EditText(this);
        key.setHint("API-ключ OpenAI (sk-…)");
        key.setSingleLine(true);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        String stored = getSharedPreferences("ai_settings", MODE_PRIVATE).getString("openai_key", "");
        if (!stored.isEmpty()) key.setText(stored);
        LinearLayout.LayoutParams keyParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        keyParams.topMargin = dp(8);
        layout.addView(key, keyParams);
        TextView privacy = new TextView(this);
        privacy.setText("Ключ хранится в настройках этого приложения на устройстве. Не передавай свой APK с ключом и не используй чужой ключ.");
        privacy.setTextColor(0xFF777D89);
        privacy.setTextSize(11);
        layout.addView(privacy);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("✦ Генератор ИИ-арта")
                .setView(layout).setNegativeButton("Отмена", null)
                .setNeutralButton("Удалить ключ", null)
                .setPositiveButton("Сгенерировать", null).create();
        dialog.setOnShowListener(x -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                getSharedPreferences("ai_settings", MODE_PRIVATE).edit().remove("openai_key").apply();
                key.setText("");
                toast("Сохранённый ключ удалён");
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String text = prompt.getText().toString().trim();
                String apiKey = key.getText().toString().trim();
                if (text.isEmpty()) { prompt.setError("Напиши, что нужно нарисовать"); return; }
                if (apiKey.isEmpty()) { key.setError("Введи API-ключ OpenAI"); return; }
                getSharedPreferences("ai_settings", MODE_PRIVATE).edit().putString("openai_key", apiKey).apply();
                dialog.dismiss();
                generateAiImage(text, apiKey);
            });
        });
        dialog.show();
    }

    private void generateAiImage(String userPrompt, String apiKey) {
        AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle("Создаю пиксель-арт…")
                .setMessage("ИИ рисует, потом я превращу картинку в пиксельную сетку. Это может занять минуту.")
                .setView(new ProgressBar(this))
                .setCancelable(false).create();
        progress.show();
        new Thread(() -> {
            Bitmap generated = null;
            String error = null;
            try {
                String tunedPrompt = "Create a high-quality retro pixel art game sprite based on this idea: " + userPrompt
                        + ". Crisp square pixels, limited harmonious palette, strong readable silhouette, centered subject, "
                        + "no text, no watermark, transparent background, made to work as a small 32x32 game sprite.";
                JSONObject body = new JSONObject();
                body.put("model", "gpt-image-1");
                body.put("prompt", tunedPrompt);
                body.put("size", "1024x1024");
                body.put("quality", "low");
                body.put("background", "transparent");
                body.put("output_format", "png");
                HttpURLConnection connection = (HttpURLConnection) new URL("https://api.openai.com/v1/images/generations").openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(180000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Authorization", "Bearer " + apiKey);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream output = connection.getOutputStream()) { output.write(payload); }
                int status = connection.getResponseCode();
                InputStream responseStream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
                String responseText;
                try (InputStream stream = responseStream; ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                    byte[] chunk = new byte[8192]; int count;
                    while ((count = stream.read(chunk)) != -1) buffer.write(chunk, 0, count);
                    responseText = buffer.toString("UTF-8");
                }
                connection.disconnect();
                if (status < 200 || status >= 300) {
                    String detail = responseText;
                    try { detail = new JSONObject(responseText).optJSONObject("error").optString("message", responseText); } catch (Exception ignored) { }
                    throw new IllegalStateException("API " + status + ": " + detail);
                }
                JSONObject result = new JSONObject(responseText);
                JSONArray data = result.getJSONArray("data");
                String encoded = data.getJSONObject(0).getString("b64_json");
                byte[] imageBytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT);
                generated = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
                if (generated == null) throw new IllegalStateException("ИИ вернул файл, который не удалось прочитать как изображение");
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final Bitmap image = generated;
            final String failure = error;
            runOnUiThread(() -> {
                progress.dismiss();
                if (failure != null) {
                    new AlertDialog.Builder(this).setTitle("Не удалось создать картинку")
                            .setMessage(friendlyAiError(failure)).setPositiveButton("ОК", null).show();
                    if (image != null && !image.isRecycled()) image.recycle();
                    return;
                }
                pushUndo();
                try {
                    if (referenceBitmap != null && !referenceBitmap.isRecycled()) referenceBitmap.recycle();
                    referenceBitmap = cropToGrid(image, gridSize);
                    pixels = bitmapToPixelArt(referenceBitmap, gridSize, 24);
                    numberMode = false; numberMap = new int[0]; numberColors = new int[0];
                    showReference = false;
                    currentTool = "Карандаш";
                    buildUi();
                    pixelCanvas.invalidate();
                    toast("ИИ-арт готов! Дорисовывай или нажми «Раскраска»");
                } catch (Exception e) {
                    toast("Картинка создана, но не обработалась: " + e.getMessage());
                } finally {
                    if (!image.isRecycled()) image.recycle();
                }
            });
        }).start();
    }

    private String friendlyAiError(String detail) {
        String lower = detail.toLowerCase(Locale.ROOT);
        if (lower.contains("invalid_api_key") || lower.contains("incorrect api key") || lower.contains("401"))
            return "Похоже, API-ключ неверный. Проверь его в платформе OpenAI и введи ещё раз.\n\n" + detail;
        if (lower.contains("quota") || lower.contains("billing") || lower.contains("insufficient_quota") || lower.contains("429"))
            return "У API может не быть доступного баланса или превышен лимит. Проверь биллинг и лимиты OpenAI.\n\n" + detail;
        return detail;
    }

    private int[] bitmapToPixelArt(Bitmap source, int size, int maxColors) {
        Bitmap scaled = Bitmap.createScaledBitmap(source, size, size, true);
        int[] raw = new int[size * size];
        scaled.getPixels(raw, 0, size, 0, 0, size, size);
        if (scaled != source) scaled.recycle();
        return quantize(raw, maxColors);
    }

    private int[] quantize(int[] raw, int maxColors) {
        List<Integer> samples = new ArrayList<>();
        for (int color : raw) if (Color.alpha(color) > 10) samples.add(color | 0xFF000000);
        int[] result = new int[raw.length];
        if (samples.isEmpty()) return result;
        int k = Math.min(maxColors, Math.min(samples.size(), 32));
        int[] centers = new int[k];
        centers[0] = samples.get(0);
        for (int c = 1; c < k; c++) {
            long bestDistance = -1;
            int bestColor = samples.get(c % samples.size());
            for (int color : samples) {
                int nearest = Integer.MAX_VALUE;
                for (int j = 0; j < c; j++) nearest = Math.min(nearest, colorDistance(color, centers[j]));
                if (nearest > bestDistance) { bestDistance = nearest; bestColor = color; }
            }
            centers[c] = bestColor;
        }
        for (int iteration = 0; iteration < 9; iteration++) {
            long[] rs = new long[k], gs = new long[k], bs = new long[k], counts = new long[k];
            for (int color : samples) {
                int closest = 0, dist = Integer.MAX_VALUE;
                for (int j = 0; j < k; j++) { int d = colorDistance(color, centers[j]); if (d < dist) { dist = d; closest = j; } }
                rs[closest] += Color.red(color); gs[closest] += Color.green(color); bs[closest] += Color.blue(color); counts[closest]++;
            }
            for (int j = 0; j < k; j++) if (counts[j] > 0) centers[j] = Color.rgb((int)(rs[j]/counts[j]), (int)(gs[j]/counts[j]), (int)(bs[j]/counts[j]));
        }
        for (int i = 0; i < raw.length; i++) {
            if (Color.alpha(raw[i]) <= 10) { result[i] = Color.TRANSPARENT; continue; }
            int closest = 0, dist = Integer.MAX_VALUE;
            for (int j = 0; j < k; j++) { int d = colorDistance(raw[i], centers[j]); if (d < dist) { dist = d; closest = j; } }
            result[i] = centers[closest];
        }
        return result;
    }

    private int colorDistance(int a, int b) {
        int dr = Color.red(a) - Color.red(b), dg = Color.green(a) - Color.green(b), db = Color.blue(a) - Color.blue(b);
        return 3 * dr * dr + 4 * dg * dg + 2 * db * db;
    }

    private void convertToNumberColoring() {
        Bitmap source = referenceBitmap;
        if (source == null) {
            boolean hasPixel = false;
            for (int px : pixels) if (Color.alpha(px) > 0) { hasPixel = true; break; }
            if (!hasPixel) {
                toast("Сначала создай ИИ-картинку или открой изображение");
                return;
            }
            source = Bitmap.createBitmap(gridSize, gridSize, Bitmap.Config.ARGB_8888);
            source.setPixels(pixels, 0, gridSize, 0, 0, gridSize, gridSize);
        }
        final Bitmap sourceImage = source;
        String[] levels = {"8 цветов — проще", "12 цветов — баланс", "16 цветов — подробнее"};
        int[] colorLimits = {8, 12, 16};
        new AlertDialog.Builder(this).setTitle("Раскраска по номерам")
                .setMessage("Картинка превратится в сетку с номерами. Выбирай цвет и нажимай клетки с таким номером.")
                .setSingleChoiceItems(levels, 1, null)
                .setNegativeButton("Отмена", (d, w) -> { if (sourceImage != referenceBitmap && !sourceImage.isRecycled()) sourceImage.recycle(); })
                .setPositiveButton("Создать раскраску", (dialog, which) -> {
                    AlertDialog picker = (AlertDialog) dialog;
                    int checked = picker.getListView().getCheckedItemPosition();
                    if (checked < 0 || checked >= colorLimits.length) checked = 1;
                    Bitmap scaled = Bitmap.createScaledBitmap(sourceImage, gridSize, gridSize, true);
                    int[] raw = new int[gridSize * gridSize];
                    scaled.getPixels(raw, 0, gridSize, 0, 0, gridSize, gridSize);
                    if (scaled != sourceImage) scaled.recycle();
                    int[] reduced = quantize(raw, colorLimits[checked]);
                    Map<Integer, Integer> ids = new HashMap<>();
                    ArrayList<Integer> colors = new ArrayList<>();
                    numberMap = new int[reduced.length];
                    for (int i = 0; i < reduced.length; i++) {
                        int color = reduced[i];
                        if (Color.alpha(color) == 0) color = Color.WHITE;
                        Integer id = ids.get(color);
                        if (id == null) { id = colors.size(); colors.add(color); ids.put(color, id); }
                        numberMap[i] = id;
                    }
                    numberColors = new int[colors.size()];
                    for (int i = 0; i < colors.size(); i++) numberColors[i] = colors.get(i);
                    numberMode = true;
                    pixels = new int[gridSize * gridSize];
                    currentColor = numberColors.length > 0 ? numberColors[0] : Color.WHITE;
                    currentTool = "Заливка";
                    undoStack.clear(); redoStack.clear();
                    if (sourceImage != referenceBitmap && !sourceImage.isRecycled()) sourceImage.recycle();
                    refreshPalette(); buildUi();
                    toast("Раскраска готова: " + numberColors.length + " цветов");
                }).show();
    }

    private void openReference() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQ_OPEN_IMAGE);
    }

    private void chooseExportMode() {
        new AlertDialog.Builder(this).setTitle("Экспорт PNG")
                .setItems(new String[]{"Только рисунок — прозрачный фон", "Рисунок + исходная картинка"}, (dialog, which) -> {
                    exportWithReference = which == 1;
                    String stamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date());
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("image/png");
                    intent.putExtra(Intent.EXTRA_TITLE, "PixelArt_" + stamp + ".png");
                    startActivityForResult(intent, REQ_SAVE_PNG);
                }).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_OPEN_IMAGE) {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                Bitmap source = BitmapFactory.decodeStream(in);
                if (source == null) throw new IllegalArgumentException("Файл не удалось прочитать как изображение");
                if (numberMode) {
                    numberMode = false; numberMap = new int[0]; numberColors = new int[0];
                    Arrays.fill(pixels, Color.TRANSPARENT);
                }
                if (referenceBitmap != null && !referenceBitmap.isRecycled()) referenceBitmap.recycle();
                referenceBitmap = cropToGrid(source, gridSize);
                source.recycle();
                showReference = true;
                if (referenceButton != null) {
                    referenceButton.setText("Реф. " + Math.round(referenceOpacity * 100) + "%");
                    referenceButton.setBackground(roundBackground(0xFFFF8738, 0xFFFFAA70, dp(9)));
                }
                if (numberMode == false) { refreshPalette(); refreshColorButton(); refreshStatus(); }
                if (pixelCanvas != null) pixelCanvas.invalidate();
                toast("Референс загружен — рисуй поверх него");
            } catch (Exception e) {
                toast("Не получилось открыть картинку: " + e.getMessage());
            }
        } else if (requestCode == REQ_SAVE_PNG) {
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("Не удалось открыть файл для записи");
                Bitmap export = makeExportBitmap(exportWithReference);
                boolean ok = export.compress(Bitmap.CompressFormat.PNG, 100, out);
                export.recycle();
                if (!ok) throw new IllegalStateException("PNG не удалось сохранить");
                toast("PNG сохранён");
            } catch (Exception e) {
                toast("Ошибка сохранения: " + e.getMessage());
            }
        }
    }

    private Bitmap cropToGrid(Bitmap src, int size) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        float sw = src.getWidth(), sh = src.getHeight();
        float side = Math.min(sw, sh);
        float left = (sw - side) / 2f;
        float top = (sh - side) / 2f;
        Rect srcRect = new Rect(Math.round(left), Math.round(top), Math.round(left + side), Math.round(top + side));
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        canvas.drawBitmap(src, srcRect, new Rect(0, 0, size, size), paint);
        return out;
    }

    private Bitmap makeExportBitmap(boolean withReference) {
        Bitmap art = Bitmap.createBitmap(gridSize, gridSize, Bitmap.Config.ARGB_8888);
        art.setPixels(pixels, 0, gridSize, 0, 0, gridSize, gridSize);
        if (!withReference || referenceBitmap == null) return art;
        Bitmap out = Bitmap.createBitmap(gridSize, gridSize, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint p = new Paint();
        p.setAlpha(255);
        canvas.drawBitmap(referenceBitmap, 0, 0, p);
        canvas.drawBitmap(art, 0, 0, p);
        art.recycle();
        return out;
    }

    private void toggleReference() {
        if (referenceBitmap == null) {
            openReference();
            return;
        }
        if (showReference) {
            showReference = false;
            referenceButton.setText("Реф. выкл.");
            referenceButton.setBackground(roundBackground(0xFF30343D, 0xFF454A55, dp(9)));
        } else {
            showReference = true;
            referenceButton.setText("Реф. " + Math.round(referenceOpacity * 100) + "%");
            referenceButton.setBackground(roundBackground(0xFFFF8738, 0xFFFFAA70, dp(9)));
        }
        pixelCanvas.invalidate();
    }

    private void toggleGrid() {
        showGrid = !showGrid;
        buildUi();
        pixelCanvas.invalidate();
        toast(showGrid ? "Сетка включена" : "Сетка выключена");
    }

    private void chooseReferenceOpacity() {
        if (referenceBitmap == null) {
            toast("Сначала загрузи исходную картинку");
            return;
        }
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(22), dp(10), dp(22), dp(4));
        TextView label = new TextView(this);
        label.setText("Непрозрачность: " + Math.round(referenceOpacity * 100) + "%");
        label.setTextColor(Color.DKGRAY);
        layout.addView(label);
        SeekBar bar = new SeekBar(this);
        bar.setMax(100);
        bar.setProgress(Math.round(referenceOpacity * 100));
        layout.addView(bar);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                label.setText("Непрозрачность: " + progress + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        new AlertDialog.Builder(this).setTitle("Прозрачность референса").setView(layout)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Применить", (d, w) -> {
                    referenceOpacity = bar.getProgress() / 100f;
                    if (referenceButton != null && showReference) referenceButton.setText("Реф. " + bar.getProgress() + "%");
                    pixelCanvas.invalidate();
                }).show();
    }

    private void chooseGridSize() {
        String[] sizes = {"16 × 16", "24 × 24", "32 × 32", "48 × 48", "64 × 64", "96 × 96", "128 × 128", "256 × 256"};
        int[] values = {16, 24, 32, 48, 64, 96, 128, 256};
        int checked = 2;
        for (int i = 0; i < values.length; i++) if (values[i] == gridSize) checked = i;
        final int[] picked = {checked};
        new AlertDialog.Builder(this).setTitle("Размер холста")
                .setSingleChoiceItems(sizes, checked, (dialog, which) -> picked[0] = which)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Изменить", (dialog, which) -> resizeGrid(values[picked[0]])).show();
    }

    private void resizeGrid(int newSize) {
        numberMode = false; numberMap = new int[0]; numberColors = new int[0];
        Bitmap oldArt = Bitmap.createBitmap(gridSize, gridSize, Bitmap.Config.ARGB_8888);
        oldArt.setPixels(pixels, 0, gridSize, 0, 0, gridSize, gridSize);
        Bitmap scaledArt = Bitmap.createBitmap(newSize, newSize, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(scaledArt);
        Paint p = new Paint();
        p.setFilterBitmap(false);
        c.drawBitmap(oldArt, null, new Rect(0, 0, newSize, newSize), p);
        int[] resized = new int[newSize * newSize];
        scaledArt.getPixels(resized, 0, newSize, 0, 0, newSize, newSize);
        oldArt.recycle();
        scaledArt.recycle();
        if (referenceBitmap != null) {
            Bitmap scaledRef = Bitmap.createBitmap(newSize, newSize, Bitmap.Config.ARGB_8888);
            Canvas rc = new Canvas(scaledRef);
            Paint rp = new Paint(); rp.setFilterBitmap(false);
            rc.drawBitmap(referenceBitmap, null, new Rect(0, 0, newSize, newSize), rp);
            referenceBitmap.recycle();
            referenceBitmap = scaledRef;
        }
        gridSize = newSize;
        pixels = resized;
        undoStack.clear(); redoStack.clear();
        buildUi();
        toast("Размер: " + gridSize + " × " + gridSize);
    }

    private void confirmClear() {
        new AlertDialog.Builder(this).setTitle("Очистить холст?")
                .setMessage("Все нарисованные пиксели будут удалены. Референс останется.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Очистить", (d, w) -> {
                    pushUndo();
                    Arrays.fill(pixels, Color.TRANSPARENT);
                    refreshStatus();
                    pixelCanvas.invalidate();
                }).show();
    }

    private void pushUndo() {
        undoStack.addLast(Arrays.copyOf(pixels, pixels.length));
        while (undoStack.size() > MAX_HISTORY) undoStack.removeFirst();
        redoStack.clear();
    }

    private void undo() {
        if (undoStack.isEmpty()) { toast("Нечего отменять"); return; }
        redoStack.addLast(Arrays.copyOf(pixels, pixels.length));
        pixels = undoStack.removeLast();
        refreshStatus();
        pixelCanvas.invalidate();
    }

    private void redo() {
        if (redoStack.isEmpty()) { toast("Нечего возвращать"); return; }
        undoStack.addLast(Arrays.copyOf(pixels, pixels.length));
        pixels = redoStack.removeLast();
        refreshStatus();
        pixelCanvas.invalidate();
    }

    private int closestColorIndex(int color, int[] colors) {
        if (colors == null || colors.length == 0) return -1;
        int best = 0, dist = Integer.MAX_VALUE;
        for (int i = 0; i < colors.length; i++) {
            int d = colorDistance(color, colors[i]);
            if (d < dist) { dist = d; best = i; }
        }
        return best;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private class PixelCanvas extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF board = new RectF();
        private float cell;
        private int lastX = -1, lastY = -1;
        private boolean strokeStarted = false;

        PixelCanvas() {
            super(MainActivity.this);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            setFocusable(true);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(0xFF242830);
            float available = Math.max(1, Math.min(getWidth() - dp(20), getHeight() - dp(12)));
            float left = (getWidth() - available) / 2f;
            float top = (getHeight() - available) / 2f;
            board.set(left, top, left + available, top + available);
            cell = available / gridSize;

            if (numberMode && numberMap.length == gridSize * gridSize) {
                paint.setStyle(Paint.Style.FILL);
                canvas.drawRect(board, paint);
                for (int y = 0; y < gridSize; y++) {
                    for (int x = 0; x < gridSize; x++) {
                        int index = y * gridSize + x;
                        int fill = pixels[index];
                        paint.setColor(Color.alpha(fill) > 0 ? fill : Color.WHITE);
                        canvas.drawRect(board.left + x * cell, board.top + y * cell,
                                board.left + (x + 1) * cell + 0.1f,
                                board.top + (y + 1) * cell + 0.1f, paint);
                        if (Color.alpha(fill) == 0 && cell >= dp(10)) {
                            paint.setColor(0xFF737782);
                            paint.setTextAlign(Paint.Align.CENTER);
                            paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                            paint.setTextSize(Math.max(dp(7), Math.min(cell * 0.52f, dp(13))));
                            canvas.drawText(String.valueOf(numberMap[index] + 1), board.left + (x + 0.5f) * cell,
                                    board.top + (y + 0.5f) * cell + paint.getTextSize() * 0.34f, paint);
                        }
                    }
                }
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1f, dp(0.7f)));
                paint.setColor(0xFFB6B8BE);
                for (int y = 0; y < gridSize; y++) for (int x = 0; x < gridSize; x++) {
                    int idx = y * gridSize + x;
                    if (x + 1 < gridSize && numberMap[idx] != numberMap[idx + 1])
                        canvas.drawLine(board.left + (x + 1) * cell, board.top + y * cell,
                                board.left + (x + 1) * cell, board.top + (y + 1) * cell, paint);
                    if (y + 1 < gridSize && numberMap[idx] != numberMap[idx + gridSize])
                        canvas.drawLine(board.left + x * cell, board.top + (y + 1) * cell,
                                board.left + (x + 1) * cell, board.top + (y + 1) * cell, paint);
                }
                paint.setColor(0xFFFF8738);
                paint.setStrokeWidth(dp(1.5f));
                canvas.drawRect(board, paint);
                paint.setStyle(Paint.Style.FILL);
                return;
            }

            // Checkerboard shows transparent pixels clearly.
            for (int y = 0; y < gridSize; y += 2) {
                for (int x = 0; x < gridSize; x += 2) {
                    paint.setColor(0xFF3A3E47);
                    canvas.drawRect(board.left + x * cell, board.top + y * cell,
                            board.left + Math.min(gridSize, x + 2) * cell,
                            board.top + Math.min(gridSize, y + 2) * cell, paint);
                    if (x + 1 < gridSize) {
                        paint.setColor(0xFF30343C);
                        canvas.drawRect(board.left + (x + 1) * cell, board.top + y * cell,
                                board.left + Math.min(gridSize, x + 3) * cell,
                                board.top + Math.min(gridSize, y + 2) * cell, paint);
                    }
                    if (y + 1 < gridSize) {
                        paint.setColor(0xFF30343C);
                        canvas.drawRect(board.left + x * cell, board.top + (y + 1) * cell,
                                board.left + Math.min(gridSize, x + 2) * cell,
                                board.top + Math.min(gridSize, y + 3) * cell, paint);
                    }
                }
            }

            if (showReference && referenceBitmap != null) {
                paint.setAlpha(Math.round(referenceOpacity * 255));
                paint.setFilterBitmap(false);
                canvas.drawBitmap(referenceBitmap, null, board, paint);
                paint.setAlpha(255);
            }
            for (int y = 0; y < gridSize; y++) {
                for (int x = 0; x < gridSize; x++) {
                    int color = pixels[y * gridSize + x];
                    if (Color.alpha(color) == 0) continue;
                    paint.setColor(color);
                    paint.setAlpha(Color.alpha(color));
                    canvas.drawRect(board.left + x * cell, board.top + y * cell,
                            board.left + (x + 1) * cell + 0.15f,
                            board.top + (y + 1) * cell + 0.15f, paint);
                }
            }
            paint.setAlpha(255);
            if (showGrid && cell >= dp(5)) {
                paint.setColor(0x55383C45);
                paint.setStrokeWidth(Math.max(0.5f, dp(0.45f)));
                for (int i = 0; i <= gridSize; i++) {
                    float pos = board.left + i * cell;
                    canvas.drawLine(pos, board.top, pos, board.bottom, paint);
                    pos = board.top + i * cell;
                    canvas.drawLine(board.left, pos, board.right, pos, paint);
                }
            }
            paint.setColor(0xFFFF8738);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.5f));
            canvas.drawRect(board, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            float x = event.getX(), y = event.getY();
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (!board.contains(x, y) || cell <= 0) return false;
                int gx = clamp((int) ((x - board.left) / cell), 0, gridSize - 1);
                int gy = clamp((int) ((y - board.top) / cell), 0, gridSize - 1);
                if (numberMode && numberMap.length == gridSize * gridSize) {
                    int index = gy * gridSize + gx;
                    if (Color.alpha(pixels[index]) > 0) return true;
                    int wanted = numberMap[index];
                    int chosen = closestColorIndex(currentColor, numberColors);
                    if (chosen == wanted) {
                        pushUndo();
                        pixels[index] = numberColors[wanted];
                        refreshStatus();
                        invalidate();
                    } else {
                        long now = System.currentTimeMillis();
                        if (now - lastWrongColorToast > 650) {
                            toast("Для этой клетки нужен цвет №" + (wanted + 1));
                            lastWrongColorToast = now;
                        }
                    }
                    return true;
                }
                lastX = gx; lastY = gy;
                strokeStarted = false;
                if ("Пипетка".equals(currentTool)) {
                    int color = pixels[gy * gridSize + gx];
                    if (Color.alpha(color) == 0 && showReference && referenceBitmap != null) color = referenceBitmap.getPixel(gx, gy);
                    if (Color.alpha(color) != 0) {
                        currentColor = color;
                        refreshPalette(); refreshColorButton();
                    }
                    return true;
                }
                if ("Заливка".equals(currentTool)) {
                    int target = pixels[gy * gridSize + gx];
                    if (target != currentColor) {
                        pushUndo();
                        floodFill(gx, gy, target, currentColor);
                        invalidate();
                    }
                    return true;
                }
                pushUndo();
                strokeStarted = true;
                paintSegment(gx, gy, gx, gy);
                invalidate();
                return true;
            } else if (event.getAction() == MotionEvent.ACTION_MOVE) {
                if (!strokeStarted || lastX < 0) return true;
                if (!board.contains(x, y)) return true;
                int gx = clamp((int) ((x - board.left) / cell), 0, gridSize - 1);
                int gy = clamp((int) ((y - board.top) / cell), 0, gridSize - 1);
                paintSegment(lastX, lastY, gx, gy);
                lastX = gx; lastY = gy;
                invalidate();
                return true;
            } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                strokeStarted = false;
                lastX = lastY = -1;
                performClick();
                invalidate();
                return true;
            }
            return true;
        }

        private void paintSegment(int x0, int y0, int x1, int y1) {
            int dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0);
            int sx = x0 < x1 ? 1 : -1;
            int sy = y0 < y1 ? 1 : -1;
            int err = dx - dy;
            while (true) {
                int idx = y0 * gridSize + x0;
                pixels[idx] = "Ластик".equals(currentTool) ? Color.TRANSPARENT : currentColor;
                if (x0 == x1 && y0 == y1) break;
                int e2 = 2 * err;
                if (e2 > -dy) { err -= dy; x0 += sx; }
                if (e2 < dx) { err += dx; y0 += sy; }
            }
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }

        private int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

        private void floodFill(int startX, int startY, int target, int replacement) {
            if (target == replacement) return;
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(startY * gridSize + startX);
            while (!queue.isEmpty()) {
                int idx = queue.removeFirst();
                if (pixels[idx] != target) continue;
                pixels[idx] = replacement;
                int x = idx % gridSize, y = idx / gridSize;
                if (x > 0 && pixels[idx - 1] == target) queue.add(idx - 1);
                if (x + 1 < gridSize && pixels[idx + 1] == target) queue.add(idx + 1);
                if (y > 0 && pixels[idx - gridSize] == target) queue.add(idx - gridSize);
                if (y + 1 < gridSize && pixels[idx + gridSize] == target) queue.add(idx + gridSize);
            }
        }
    }
}
