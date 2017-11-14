package io.enoy.tg.action;

import org.telegram.telegrambots.api.objects.Message;

public interface SimpleTgActionRegexDefinition extends TgActionRegexDefinition<TgActionContext> {

    @Override
    default Class<TgActionContext> getActionContextClass(){
        return TgActionContext.class;
    }

    @Override
    default TgActionContext createAction(Message message) {
        return new TgActionContext();
    }
}
