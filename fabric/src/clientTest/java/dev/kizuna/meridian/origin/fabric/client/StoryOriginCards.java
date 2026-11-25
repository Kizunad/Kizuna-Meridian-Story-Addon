package dev.kizuna.meridian.origin.fabric.client;

import dev.kizuna.meridian.story.fabric.PhysiqueScene;
import java.util.Set;
import java.util.stream.Collectors;

/** 通过正式出身界面的挑选和确认回调操作，截图前核实屏幕上的卡片。 */
public final class StoryOriginCards {
    private StoryOriginCards() {
        // 复用当前屏幕里的候选与按钮。
    }

    public static boolean ordinaryAndFarmer(OriginScreen screen) {
        // 候选必须恰好是这两张，且当前视口真的并排排版。
        var offer = screen.offer();
        var ids = offer.getCardsList().stream().map(card -> card.getId()).collect(Collectors.toSet());
        return !offer.getChosen() && screen.sideBySide()
                && ids.equals(Set.of(PhysiqueScene.ORDINARY, PhysiqueScene.FARMER));
    }

    public static boolean chosen(OriginScreen screen, String id) {
        // 复看时宿主只发送已选出身。
        var offer = screen.offer();
        return offer.getChosen() && offer.getCardsCount() == 1 && offer.getCards(0).getId().equals(id);
    }

    public static void chooseOrdinary(OriginScreen screen) {
        // 与玩家点「就是它」再点确认经过同一条发送路径。
        var card = screen.offer().getCardsList().stream().filter(value -> value.getId().equals(PhysiqueScene.ORDINARY))
                .findFirst().orElseThrow();
        screen.pick(card);
        screen.settle();
    }
}
