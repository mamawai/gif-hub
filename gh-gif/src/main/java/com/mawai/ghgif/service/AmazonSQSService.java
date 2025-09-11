package com.mawai.ghgif.service;

public interface AmazonSQSService {

    void send(String message, String queueUrl);

    void delete(String messageRecipe, String queueUrl);

}
