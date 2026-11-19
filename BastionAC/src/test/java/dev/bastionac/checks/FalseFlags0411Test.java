package dev.bastionac.checks;

import dev.bastionac.core.XrayTracker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The false flags of the 03.11 logs. */
class FalseFlags0411Test {
    @Test
    @DisplayName("Падение вдоль стены: 1,25 блока за тик — ещё падение, счёт не выставляем")
    void wallBrushIsStillAFall() {
        assertTrue(MovementChecks.stillFalling(-1.25));
        assertFalse(MovementChecks.stillFalling(0.0));
        assertFalse(MovementChecks.stillFalling(-0.1));
    }

    @Test
    @DisplayName("Подброс сервером: подъём не считается Spider и HighJump до конца окна")
    void serverThrow() {
        assertTrue(MovementChecks.thrownUp(100, 106, 60), "эхо 0,42 после урона от падения");
        assertTrue(MovementChecks.thrownUp(100, 108, 60), "взрыв, 2,82 блока");
        assertFalse(MovementChecks.thrownUp(100, 161, 60), "окно вышло");
        assertFalse(MovementChecks.thrownUp(-1, 5, 60), "подброса не было");
    }

    @Test
    @DisplayName("Шаг на полублок во время падения: числа из лога 03.11, 76,2966 → 76,5")
    void stepUpInAFall() {
        assertTrue(MovementChecks.steppedUp(false, -0.2278, 76.2966, 76.5), "шаг вверх при падении");
        assertTrue(MovementChecks.steppedUp(true, 0.0, 75.0, 75.5), "обычный шаг с земли");
        assertFalse(MovementChecks.steppedUp(false, 0.33, 76.0, 76.3), "подъём в прыжке — не шаг");
        assertFalse(MovementChecks.steppedUp(false, -0.2, 76.0, 76.8), "выше ступеньки — не шаг");
    }

    @Test
    @DisplayName("X-ray: без единой скрытой руды вердикта нет")
    void noBuriedOreNoVerdict() {
        assertFalse(XrayTracker.foundBuried(0));
        assertFalse(XrayTracker.foundBuried(1));
        assertTrue(XrayTracker.foundBuried(2));
    }
}
