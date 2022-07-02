/*
 * SonarQube
 * Copyright (C) 2009-2022 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.usertoken.ws;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.sonar.api.server.ws.Change;
import org.sonar.api.server.ws.Request;
import org.sonar.api.server.ws.Response;
import org.sonar.api.server.ws.WebService;
import org.sonar.api.utils.System2;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.user.TokenType;
import org.sonar.db.user.UserDto;
import org.sonar.db.user.UserTokenDto;
import org.sonar.server.exceptions.ServerException;
import org.sonar.server.usertoken.TokenGenerator;
import org.sonarqube.ws.UserTokens;
import org.sonarqube.ws.UserTokens.GenerateWsResponse;

import static com.google.common.base.Preconditions.checkArgument;
import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR;
import static org.sonar.api.utils.DateUtils.formatDateTime;
import static org.sonar.db.user.TokenType.GLOBAL_ANALYSIS_TOKEN;
import static org.sonar.db.user.TokenType.PROJECT_ANALYSIS_TOKEN;
import static org.sonar.db.user.TokenType.USER_TOKEN;
import static org.sonar.server.exceptions.BadRequestException.checkRequest;
import static org.sonar.server.usertoken.ws.UserTokenSupport.ACTION_GENERATE;
import static org.sonar.server.usertoken.ws.UserTokenSupport.PARAM_EXPIRATION_DATE;
import static org.sonar.server.usertoken.ws.UserTokenSupport.PARAM_LOGIN;
import static org.sonar.server.usertoken.ws.UserTokenSupport.PARAM_NAME;
import static org.sonar.server.usertoken.ws.UserTokenSupport.PARAM_PROJECT_KEY;
import static org.sonar.server.usertoken.ws.UserTokenSupport.PARAM_TYPE;
import static org.sonar.server.ws.WsUtils.writeProtobuf;

public class GenerateAction implements UserTokensWsAction {

  private static final int MAX_TOKEN_NAME_LENGTH = 100;

  private final DbClient dbClient;
  private final System2 system;
  private final TokenGenerator tokenGenerator;
  private final UserTokenSupport userTokenSupport;

  public GenerateAction(DbClient dbClient, System2 system, TokenGenerator tokenGenerator, UserTokenSupport userTokenSupport) {
    this.dbClient = dbClient;
    this.system = system;
    this.tokenGenerator = tokenGenerator;
    this.userTokenSupport = userTokenSupport;
  }

  @Override
  public void define(WebService.NewController context) {
    WebService.NewAction action = context.createAction(ACTION_GENERATE)
      .setSince("5.3")
      .setPost(true)
      .setDescription("Generate a user access token. <br />" +
        "Please keep your tokens secret. They enable to authenticate and analyze projects.<br />" +
        "It requires administration permissions to specify a 'login' and generate a token for another user. Otherwise, a token is generated for the current user.")
      .setChangelog(
        new Change("9.6", "Response field 'expirationDate' added"))
      .setResponseExample(getClass().getResource("generate-example.json"))
      .setHandler(this);

    action.createParam(PARAM_LOGIN)
      .setDescription("User login. If not set, the token is generated for the authenticated user.")
      .setExampleValue("g.hopper");

    action.createParam(PARAM_NAME)
      .setRequired(true)
      .setMaximumLength(MAX_TOKEN_NAME_LENGTH)
      .setDescription("Token name")
      .setExampleValue("Project scan on Travis");

    action.createParam(PARAM_TYPE)
      .setSince("9.5")
      .setDescription("Token Type. If this parameters is set to " + PROJECT_ANALYSIS_TOKEN.name() + ", it is necessary to provide the projectKey parameter too.")
      .setPossibleValues(USER_TOKEN.name(), GLOBAL_ANALYSIS_TOKEN.name(), PROJECT_ANALYSIS_TOKEN.name())
      .setDefaultValue(USER_TOKEN.name());

    action.createParam(PARAM_PROJECT_KEY)
      .setSince("9.5")
      .setDescription("The key of the only project that can be analyzed by the " + PROJECT_ANALYSIS_TOKEN.name() + " being generated.");

    action.createParam(PARAM_EXPIRATION_DATE)
      .setSince("9.6")
      .setDescription("The expiration date of the token being generated, in ISO 8601 format (YYYY-MM-DD).");
  }

  @Override
  public void handle(Request request, Response response) throws Exception {
    UserTokens.GenerateWsResponse generateWsResponse = doHandle(request);
    writeProtobuf(generateWsResponse, request, response);
  }

  private UserTokens.GenerateWsResponse doHandle(Request request) {
    try (DbSession dbSession = dbClient.openSession(false)) {
      String token = generateToken(request, dbSession);
      String tokenHash = hashToken(dbSession, token);

      UserTokenDto userTokenDtoFromRequest = getUserTokenDtoFromRequest(request);
      userTokenDtoFromRequest.setTokenHash(tokenHash);

      UserDto user = userTokenSupport.getUser(dbSession, request);
      userTokenDtoFromRequest.setUserUuid(user.getUuid());

      UserTokenDto userTokenDto = insertTokenInDb(dbSession, user, userTokenDtoFromRequest);

      return buildResponse(userTokenDto, token, user);
    }
  }

  private UserTokenDto getUserTokenDtoFromRequest(Request request) {
    UserTokenDto userTokenDtoFromRequest = new UserTokenDto()
      .setName(request.mandatoryParam(PARAM_NAME).trim())
      .setCreatedAt(system.now())
      .setType(getTokenTypeFromRequest(request).name())
      .setExpirationDate(getExpirationDateFromRequest(request));

    getProjectKeyFromRequest(request).ifPresent(userTokenDtoFromRequest::setProjectKey);

    return userTokenDtoFromRequest;
  }

  private static Long getExpirationDateFromRequest(Request request) {
    String expirationDateString = request.param(PARAM_EXPIRATION_DATE);
    Long expirationDateOpt = null;

    if (expirationDateString != null) {
      try {
        expirationDateOpt = getExpirationDateFromString(expirationDateString);
      } catch (DateTimeParseException e) {
        throw new IllegalArgumentException(String.format("Supplied date format for parameter %s is wrong. Please supply date in the ISO 8601 " +
          "date format (YYYY-MM-DD)", PARAM_EXPIRATION_DATE));
      }
    }

    return expirationDateOpt;
  }

  @NotNull
  private static Long getExpirationDateFromString(String expirationDateString) {
    LocalDate expirationDate = LocalDate.parse(expirationDateString, DateTimeFormatter.ISO_DATE);
    validateExpirationDateValue(expirationDate);
    return expirationDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
  }

  private static void validateExpirationDateValue(LocalDate localDate) {
    if (localDate.isBefore(LocalDate.now().plusDays(1))) {
      throw new IllegalArgumentException(
        String.format("The minimum value for parameter %s is %s.", PARAM_EXPIRATION_DATE, LocalDate.now().plusDays(1).format(DateTimeFormatter.ISO_DATE)));
    }
  }

  private String generateToken(Request request, DbSession dbSession) {
    TokenType tokenType = getTokenTypeFromRequest(request);
    validateParametersCombination(dbSession, request, tokenType);
    return tokenGenerator.generate(tokenType);
  }

  private void validateParametersCombination(DbSession dbSession, Request request, TokenType tokenType) {
    if (PROJECT_ANALYSIS_TOKEN.equals(tokenType)) {
      validateProjectAnalysisParameters(dbSession, request);
    } else if (GLOBAL_ANALYSIS_TOKEN.equals(tokenType)) {
      validateGlobalAnalysisParameters(request);
    }
  }

  private void validateProjectAnalysisParameters(DbSession dbSession, Request request) {
    checkArgument(userTokenSupport.sameLoginAsConnectedUser(request), "A Project Analysis Token cannot be generated for another user.");
    checkArgument(request.param(PARAM_PROJECT_KEY) != null, "A projectKey is needed when creating Project Analysis Token");
    userTokenSupport.validateProjectScanPermission(dbSession, getProjectKeyFromRequest(request).orElse(""));
  }

  private void validateGlobalAnalysisParameters(Request request) {
    checkArgument(userTokenSupport.sameLoginAsConnectedUser(request), "A Global Analysis Token cannot be generated for another user.");
    userTokenSupport.validateGlobalScanPermission();
  }

  private static Optional<String> getProjectKeyFromRequest(Request request) {
    String projectKey = null;
    if (PROJECT_ANALYSIS_TOKEN.equals(getTokenTypeFromRequest(request))) {
      projectKey = request.mandatoryParam(PARAM_PROJECT_KEY).trim();
    }
    return Optional.ofNullable(projectKey);
  }

  private static TokenType getTokenTypeFromRequest(Request request) {
    String tokenTypeValue = request.mandatoryParam(PARAM_TYPE).trim();
    return TokenType.valueOf(tokenTypeValue);
  }

  private String hashToken(DbSession dbSession, String token) {
    String tokenHash = tokenGenerator.hash(token);
    UserTokenDto userToken = dbClient.userTokenDao().selectByTokenHash(dbSession, tokenHash);
    if (userToken == null) {
      return tokenHash;
    }
    throw new ServerException(HTTP_INTERNAL_ERROR, "Error while generating token. Please try again.");
  }

  private UserTokenDto insertTokenInDb(DbSession dbSession, UserDto user,UserTokenDto userTokenDto) {
    checkTokenDoesNotAlreadyExists(dbSession, user, userTokenDto.getName());
    dbClient.userTokenDao().insert(dbSession, userTokenDto, user.getLogin());
    dbSession.commit();
    return userTokenDto;
  }

  private void checkTokenDoesNotAlreadyExists(DbSession dbSession, UserDto user, String name) {
    UserTokenDto userTokenDto = dbClient.userTokenDao().selectByUserAndName(dbSession, user, name);
    checkRequest(userTokenDto == null, "A user token for login '%s' and name '%s' already exists", user.getLogin(), name);
  }

  private static GenerateWsResponse buildResponse(UserTokenDto userTokenDto, String token, UserDto user) {
    GenerateWsResponse.Builder responseBuilder = GenerateWsResponse.newBuilder()
      .setLogin(user.getLogin())
      .setName(userTokenDto.getName())
      .setCreatedAt(formatDateTime(userTokenDto.getCreatedAt()))
      .setToken(token)
      .setType(userTokenDto.getType());

    if (userTokenDto.getProjectKey() != null) {
      responseBuilder.setProjectKey(userTokenDto.getProjectKey());
    }

    if (userTokenDto.getExpirationDate() != null) {
      responseBuilder.setExpirationDate(formatDateTime(userTokenDto.getExpirationDate()));
    }

    return responseBuilder.build();
  }

}
