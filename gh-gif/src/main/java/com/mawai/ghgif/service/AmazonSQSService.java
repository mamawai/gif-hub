package com.mawai.ghgif.service;

import com.mawai.ghgif.constant.MessageType;

public interface AmazonSQSService {

    void send(String message, String queueUrl, MessageType messageType);

    void delete(String messageRecipe, String queueUrl);
}
