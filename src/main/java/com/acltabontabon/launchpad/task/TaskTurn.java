package com.acltabontabon.launchpad.task;

/** One round of the /new-task interview: the model's question and the user's answer. */
public record TaskTurn(String question, String answer) {}
