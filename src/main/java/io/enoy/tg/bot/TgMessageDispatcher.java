package io.enoy.tg.bot;

import io.enoy.tg.action.CommandValidator;
import io.enoy.tg.action.TgAction;
import io.enoy.tg.action.TgActionRequestHandler;
import io.enoy.tg.action.request.TgParameterType.NonMatchingTypeException;
import io.enoy.tg.action.request.TgRequestHandlerHopsComposition;
import io.enoy.tg.action.request.TgRequestResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.api.objects.Message;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Scope("tg")
@RequiredArgsConstructor
@Slf4j
public class TgMessageDispatcher {

	private final ApplicationContext context;
	private final Set<TgAction> actions;
	private final List<Message> currentMessages = new ArrayList<>();
	private TgAction currentAction;

	public void dispatch(Message message) throws TgDispatchException {

		if (Objects.isNull(currentAction)) {
			currentAction = resolveAction(message);
		}

		Object controllerClass = context.getBean(currentAction.getControllerClass());

		currentMessages.add(message);

		TgActionRequestHandler handler = resolveHandler();

		TgRequestResult result = TgRequestResult.OK;

		if (Objects.nonNull(handler)) {
			result = handler.execute(controllerClass, currentMessages);
		}

		handleResult(result);

		if (isAtLastParameter()) {
			clear();
		}
	}

	private void handleResult(TgRequestResult result) {
		switch (result) {
			case RETRY:
				currentMessages.remove(currentMessages.size() - 1);
				break;
			case ABORT:
				clear();
				break;
		}
	}

	public void clear() {
		currentMessages.clear();
		currentAction = null;
	}

	private boolean isAtLastParameter() {
		return currentMessages.size() >= getCurrentActionMaxParameterCount();
	}

	private int getCurrentActionMaxParameterCount() {
		OptionalInt optMaxParameterCount =
				currentAction.getRequestHandlers().stream()
						.mapToInt(TgActionRequestHandler::getParameterCount)
						.max();

		return optMaxParameterCount.isPresent() ? optMaxParameterCount.getAsInt() : -1;
	}

	private TgActionRequestHandler resolveHandler() throws TgDispatchException {
		checkHandlers();

		List<TgRequestHandlerHopsComposition> requestHandlersWithHops = getTgRequestHandlerWithHops();

		if (requestHandlersWithHops.isEmpty())
			return null;

		return getMinimalHopHandler(requestHandlersWithHops);

	}

	private List<TgRequestHandlerHopsComposition> getTgRequestHandlerWithHops() {
		return currentAction.getRequestHandlers().stream()
				.filter(tgActionRequestHandler -> tgActionRequestHandler.getParameterCount() == currentMessages.size())
				.map(tgActionRequestHandler -> {
					try {
						return tgActionRequestHandler.getHops(currentMessages);
					} catch (NonMatchingTypeException e) {
						return null;
					}
				})
				.filter(Objects::nonNull)
				.sorted()
				.collect(Collectors.toList());
	}

	private TgActionRequestHandler getMinimalHopHandler(List<TgRequestHandlerHopsComposition> requestHandlersWithHops) throws TgDispatchException {
		int mostHops = requestHandlersWithHops.stream()
				.mapToInt(TgRequestHandlerHopsComposition::getHops)
				.max()
				.orElseGet(() -> {
					throw new Error("could not gather maximum hop amount. This should never happen wtf...");
				});

		for (int i = 0; i <= mostHops; i++) {
			int finalI = i;
			List<TgRequestHandlerHopsComposition> minHopsHandlers =
					requestHandlersWithHops.stream()
							.filter(tgRequestHandlerHopsComposition -> tgRequestHandlerHopsComposition.getHops() == finalI)
							.collect(Collectors.toList());

			if (minHopsHandlers.size() > 1) {
				throw new TgDispatchException("Multiple handlers found!");
			} else if (minHopsHandlers.size() == 1) {
				return minHopsHandlers.get(0).getHandler();
			}
		}
		return null;
	}

	private void checkHandlers() throws TgDispatchException {
		if (currentAction.getRequestHandlers().isEmpty())
			throw new TgDispatchException("No handlers are defined!");
	}

	private TgAction resolveAction(Message message) throws TgDispatchException {
		List<TgAction> matchingActions = findMatchingActions(message);
		if (matchingActions.size() > 1) {
			log.error("Multiple matching commands found: {}", getCommandNamesJoined(matchingActions));
			throw new TgDispatchException("Multiple matching commands found!");
		} else if (matchingActions.size() == 0) {
			throw new TgDispatchException("Command not found");
		} else {
			return matchingActions.get(0);
		}
	}

	private String getCommandNamesJoined(List<TgAction> matchingActions) {
		return matchingActions.stream().map(TgAction::getName).collect(Collectors.joining(", "));
	}

	private List<TgAction> findMatchingActions(final Message message) {
		return
				actions.stream()
						.filter(action -> {
							if (action.isCommandValidatorExisting(message)) {
								CommandValidator validator = getCommandValidator(action.getCommandValidatorClass());
								return validator.validate(message);
							} else {
								if (message.hasText() && action.hasRegex()) {
									return action.isRegexMatching(message.getText());
								}
							}

							return false;
						}).collect(Collectors.toList());


	}

	private CommandValidator getCommandValidator(Class<? extends CommandValidator> commandValidatorClass) {
		return context.getBean(commandValidatorClass);
	}

	public static class TgDispatchException extends Exception {
		public TgDispatchException(String message) {
			super(message);
		}
	}

}