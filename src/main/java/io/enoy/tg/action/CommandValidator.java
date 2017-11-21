package io.enoy.tg.action;

import org.telegram.telegrambots.api.objects.Message;

@FunctionalInterface
public interface CommandValidator {

	boolean validate(Message message);

}