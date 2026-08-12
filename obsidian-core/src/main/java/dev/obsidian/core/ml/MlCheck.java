package dev.obsidian.core.ml;

import dev.obsidian.core.ObsidianPlugin;
import dev.obsidian.core.check.Check;
import dev.obsidian.core.tracker.ActionRecord;
import dev.obsidian.core.tracker.ActionType;
import dev.obsidian.core.tracker.PlayerData;
import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * The machine-learning layer, expressed as one more check. It builds a feature
 * vector from each window of a player's combat, scores it with the logistic
 * model, and emits a proportional signal when the score clears the threshold.
 *
 * <p>Deliberately just one voice in the confidence engine: the model corroborates
 * the deterministic checks, it never convicts on its own. It is experimental and
 * modestly weighted, and — like every check — stands down during lag or an
 * unstable connection. When dataset logging is on, each finalized window is
 * written for the offline trainer, tagged with the player's staff label.</p>
 */
public final class MlCheck extends Check {

    private final ObsidianPlugin plugin;

    private Model model;
    private DatasetLogger datasetLogger;

    private boolean datasetLogging;
    private double threshold;
    private int windowActions;
    private double angleViolationThreshold;
    private double reachSlop;

    public MlCheck(ObsidianPlugin plugin) {
        super("ml", "ml", true);
        this.plugin = plugin;
    }

    @Override
    protected void loadSettings(ConfigurationSection section) {
        threshold = section.getDouble("threshold", 0.85);
        windowActions = section.getInt("window-actions", 40);
        datasetLogging = section.getBoolean("dataset-logging", false);
        angleViolationThreshold = section.getDouble("angle-violation-threshold", 65.0);
        reachSlop = section.getDouble("reach-slop", 0.1);

        model = loadModel();
        if (datasetLogging && datasetLogger == null) {
            datasetLogger = new DatasetLogger(plugin.getDataFolder(), plugin.getLogger());
        }
    }

    private Model loadModel() {
        // A retrained model dropped in the data folder wins over the shipped one.
        File external = new File(plugin.getDataFolder(), "model.dat");
        if (external.isFile()) {
            try (InputStream in = new java.io.FileInputStream(external)) {
                return LogisticModel.load(in);
            } catch (IOException | RuntimeException e) {
                plugin.getLogger().warning("Failed to load model.dat from data folder ("
                        + e.getMessage() + "); using bundled model.");
            }
        }
        try (InputStream in = plugin.getResource("model.dat")) {
            if (in != null) {
                return LogisticModel.load(in);
            }
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().warning("Failed to load bundled model.dat: " + e.getMessage());
        }
        return null;
    }

    private static final class State {
        final FeatureExtractor extractor = new FeatureExtractor();
    }

    @Override
    public void onAction(PlayerData data, ActionRecord action) {
        if (model == null || data.lagContext.shouldSkip()) {
            return;
        }
        State state = data.checkState(slot(), State::new);
        FeatureExtractor fx = state.extractor;

        switch (action.type) {
            case ENTITY_ATTACK -> fx.onEntityAttack(action.targetIsPlayer, action.reachDistance,
                    action.reachLimit, action.angleToTarget, action.withMace, action.nanoTime,
                    action.targetEntityId, angleViolationThreshold, reachSlop);
            case WIND_CHARGE_USE -> fx.onWindCharge(action.nanoTime);
            case SWING -> {
                if (data.inCombat(action.nanoTime, 5_000_000_000L)) {
                    fx.onSwingInCombat(action.nanoTime);
                }
                return; // swings don't count toward the window size
            }
            default -> {
                return;
            }
        }

        if (fx.ready(windowActions)) {
            double[] vector = fx.finalizeWindow();
            double score = model.score(vector);
            if (datasetLogging && datasetLogger != null) {
                String label = data.datasetLabel;
                String name = data.name();
                plugin.scheduler().runAsync(() -> datasetLogger.log(label, name, vector));
            }
            if (score >= threshold) {
                double strength = Math.min(1.0, (score - threshold) / Math.max(1e-6, 1.0 - threshold));
                signal(data, strength, "model score " + Math.round(score * 100) / 100.0
                        + " (" + model.id() + ")");
            }
        }
    }

    @Override
    public void onRotation(PlayerData data) {
        if (model == null || data.lagContext.shouldSkip() || data.rotations.size() < 2) {
            return;
        }
        State state = data.checkState(slot(), State::new);
        double mag = Math.hypot(data.rotations.deltaYaw(0), data.rotations.deltaPitch(0));
        state.extractor.onRotation(mag);
    }

    public String modelId() {
        return model == null ? "none (model failed to load)" : model.id();
    }

    public boolean datasetLogging() {
        return datasetLogging;
    }

    public void shutdown() {
        if (datasetLogger != null) {
            datasetLogger.close();
        }
    }
}
