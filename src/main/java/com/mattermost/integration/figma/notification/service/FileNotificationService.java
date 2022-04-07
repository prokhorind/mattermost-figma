package com.mattermost.integration.figma.notification.service;

import com.mattermost.integration.figma.api.figma.webhook.dto.TeamWebhookInfoResponseDto;
import com.mattermost.integration.figma.api.figma.webhook.service.FigmaWebhookService;
import com.mattermost.integration.figma.api.mm.dm.dto.DMChannelPayload;
import com.mattermost.integration.figma.api.mm.dm.dto.DMMessagePayload;
import com.mattermost.integration.figma.api.mm.dm.service.DMMessageService;
import com.mattermost.integration.figma.api.mm.kv.KVService;
import com.mattermost.integration.figma.input.file.notification.FigmaWebhookResponse;
import com.mattermost.integration.figma.input.file.notification.FileCommentNotificationRequest;
import com.mattermost.integration.figma.input.file.notification.FileCommentWebhookResponse;
import com.mattermost.integration.figma.input.oauth.ActingUser;
import com.mattermost.integration.figma.input.oauth.Context;
import com.mattermost.integration.figma.input.oauth.InputPayload;
import com.mattermost.integration.figma.security.dto.UserDataDto;
import com.mattermost.integration.figma.utils.json.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

@Service
@Slf4j
public class FileNotificationService {
    private static final String BASE_WEBHOOK_URL = "https://api.figma.com/v2/webhooks";
    private static final String PASSCODE = "Mattermost";
    private static final String FILE_COMMENT_EVENT_TYPE = "FILE_COMMENT";
    //Production Mattermost link
    private static final String REDIRECT_URL = "http://localhost:8065/plugins/com.mattermost.apps/apps/spring-boot-example%s?secret=%s";
    private static final String FILE_COMMENT_URL = "/webhook/comment";

    private final RestTemplate restTemplate;
    private final DMMessageService messageService;
    private final KVService kvService;
    private final JsonUtils jsonUtils;
    private final FigmaWebhookService figmaWebhookService;

    public FileNotificationService(RestTemplate restTemplate, DMMessageService messageService, KVService kvService, JsonUtils jsonUtils, FigmaWebhookService figmaWebhookService) {
        this.restTemplate = restTemplate;
        this.messageService = messageService;
        this.kvService = kvService;
        this.jsonUtils = jsonUtils;
        this.figmaWebhookService = figmaWebhookService;
    }

    public String subscribeToFileNotification(InputPayload inputPayload) {
        String teamId = inputPayload.getValues().getTeamId();
        if (teamId != null && !teamId.isEmpty() && !teamId.isBlank()) {
            if (checkIfTeamWebhookIsPresent(teamId, inputPayload.getContext().getOauth2().getUser().getAccessToken())) {
                HttpEntity<FileCommentNotificationRequest> request = createFileCommentNotificationRequest(inputPayload);
                log.debug("File notification request : " + request);
                log.info("Sending comment request for team with id: " + teamId);
                return restTemplate.postForEntity(BASE_WEBHOOK_URL, request, String.class).toString();
            }
            return "Success";
        }
        return null;
    }


    public void saveUserData(InputPayload inputPayload) {
        String userId = inputPayload.getContext().getOauth2().getUser().getUserId();
        String mmSiteUrl = inputPayload.getContext().getMattermostSiteUrl();
        String botAccessToken = inputPayload.getContext().getBotAccessToken();
        String teamId = inputPayload.getValues().getTeamId();

        UserDataDto currentData = getCurrentUserData(userId, mmSiteUrl, botAccessToken);
        if (Objects.nonNull(currentData.getTeamIds()) && !currentData.getTeamIds().isEmpty()) {
            currentData.getTeamIds().add(teamId);
            kvService.put(userId, currentData, mmSiteUrl, botAccessToken);
        } else {
            UserDataDto newUserData = new UserDataDto();
            newUserData.setTeamIds(new HashSet<>(Collections.singletonList(teamId)));
            newUserData.setMmUserId(inputPayload.getContext().getActingUser().getId());
            kvService.put(userId, newUserData, mmSiteUrl, botAccessToken);
        }
    }

    public void sendFileNotificationMessageToMM(FileCommentWebhookResponse fileCommentWebhookResponse) {
        FigmaWebhookResponse figmaWebhookResponse = fileCommentWebhookResponse.values.data;
        Context context = fileCommentWebhookResponse.context;
        if (!figmaWebhookResponse.getMentions().isEmpty()) {
            figmaWebhookResponse.getMentions().stream().map((mention ->
                    getCurrentUserData(mention.id, context.getMattermostSiteUrl(), context.getBotAccessToken())))
                    .forEach(userData -> sendMessageToSpecificReceiver(context, userData, figmaWebhookResponse));
        }
    }

    private void sendMessageToSpecificReceiver(Context context, UserDataDto specificUserData, FigmaWebhookResponse figmaWebhookResponse) {
        context.setActingUser(new ActingUser());
        context.getActingUser().setId(specificUserData.getMmUserId());
        String channelId = messageService.createDMChannel(createDMChannelPayload(context));
        messageService.sendDMMessage(createDMMessagePayload(channelId, context.getBotAccessToken(),
                context.getMattermostSiteUrl(), figmaWebhookResponse));
    }

    private boolean checkIfTeamWebhookIsPresent(String teamId, String figmaToken) {
        TeamWebhookInfoResponseDto teamWebhooks = figmaWebhookService.getTeamWebhooks(teamId, figmaToken);
        teamWebhooks.getWebhooks().forEach(webhook -> figmaWebhookService.deleteWebhook(webhook.getId(), figmaToken));
        return teamWebhooks.getWebhooks().stream().anyMatch(webhook -> webhook.getEventType().equals(FILE_COMMENT_EVENT_TYPE));
    }

    private DMChannelPayload createDMChannelPayload(Context context) {
        String botAccessToken = context.getBotAccessToken();
        String botUserId = context.getBotUserId();
        String userId = context.getActingUser().getId();
        String mattermostSiteUrl = context.getMattermostSiteUrl();
        return new DMChannelPayload(userId, botUserId, botAccessToken, mattermostSiteUrl);
    }

    private DMMessagePayload createDMMessagePayload(String channelId, String botAccessToken,
                                                    String mmSiteUrl, FigmaWebhookResponse figmaWebhookResponse) {
        DMMessagePayload message = new DMMessagePayload();
        message.setChannelId(channelId);
        message.setMessage(createFileNotificationMessage(figmaWebhookResponse));
        message.setToken(botAccessToken);
        message.setMmSiteUrlBase(mmSiteUrl);
        return message;
    }

    private boolean checkIfUserTeamIsPresent(InputPayload inputPayload) {
        String userId = inputPayload.getContext().getOauth2().getUser().getUserId();
        String mmSiteUrl = inputPayload.getContext().getMattermostSiteUrl();
        String botAccessToken = inputPayload.getContext().getBotAccessToken();
        Set<String> teamIds = getCurrentUserData(userId, mmSiteUrl, botAccessToken).getTeamIds();
        return Objects.nonNull(teamIds) && teamIds.contains(inputPayload.getValues().getTeamId());
    }

    private UserDataDto getCurrentUserData(String userId, String mmSiteUrl, String botAccessToken) {
        return (UserDataDto) jsonUtils.convertStringToObject(kvService.get(userId, mmSiteUrl,
                botAccessToken), UserDataDto.class).get();
    }

    private String createFileNotificationMessage(FigmaWebhookResponse figmaWebhookResponse) {
        return String.format("User %s mentioned you in a comment %s in a file %s",
                figmaWebhookResponse.getTriggeredBy().getHandle(),
                figmaWebhookResponse.getComment()[0].getText(),
                figmaWebhookResponse.getFileName());
    }

    private HttpEntity<FileCommentNotificationRequest> createFileCommentNotificationRequest(InputPayload inputPayload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", String.format("Bearer %s", inputPayload.getContext().getOauth2().getUser().getAccessToken()));

        FileCommentNotificationRequest fileCommentNotificationRequest = new FileCommentNotificationRequest();
        fileCommentNotificationRequest.setEventType(FILE_COMMENT_EVENT_TYPE);
        fileCommentNotificationRequest.setTeamId(inputPayload.getValues().getTeamId());
        fileCommentNotificationRequest.setPasscode(PASSCODE);
        //For production Mattermost link
        fileCommentNotificationRequest.setEndpoint(String.format(REDIRECT_URL, FILE_COMMENT_URL,
                inputPayload.getContext().getApp().getWebhookSecret()));

        return new HttpEntity<>(fileCommentNotificationRequest, headers);
    }
}