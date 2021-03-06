package cloudos.resources;

import cloudos.dao.AccountDAO;
import cloudos.dao.AppDAO;
import cloudos.model.Account;
import cloudos.model.auth.AuthenticationException;
import cloudos.model.auth.ChangePasswordRequest;
import cloudos.model.auth.CloudOsAuthResponse;
import cloudos.model.auth.LoginRequest;
import cloudos.model.support.AccountRequest;
import cloudos.server.CloudOsConfiguration;
import com.qmino.miredot.annotations.ReturnType;
import lombok.extern.slf4j.Slf4j;
import org.cobbzilla.mail.TemplatedMail;
import org.cobbzilla.mail.service.TemplatedMailService;
import org.cobbzilla.util.time.TimeUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.validation.Valid;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.ArrayList;

import static cloudos.resources.ApiConstants.ACCOUNTS_ENDPOINT;
import static org.cobbzilla.wizard.resources.ResourceUtil.*;

@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Path(ACCOUNTS_ENDPOINT)
@Service @Slf4j
public class AccountsResource extends AccountsResourceBase<Account, CloudOsAuthResponse> {

    public static final String PARAM_NAME = "name";
    public static final String EP_CHANGE_PASSWORD = "/{"+PARAM_NAME+"}/password";
    public static String getChangePasswordPath(String name) { return ACCOUNTS_ENDPOINT + EP_CHANGE_PASSWORD.replace("{"+PARAM_NAME+"}", name); }

    protected void beforeSessionStart(LoginRequest login, Account account) throws Exception {
        // keep the password in the session, it'll be scrubbed from the json response
        account.setPassword(login.getPassword());
    }

    @Override protected void afterSuccessfulLogin(LoginRequest login, Account account) throws Exception {
        // set apps
        account.setAvailableApps(new ArrayList<>(appDAO.getAvailableAppDetails().values()));
    }

    @Override
    protected CloudOsAuthResponse buildAuthResponse(String sessionId, Account account) {
        return new CloudOsAuthResponse(sessionId, account);
    }

    @Autowired private AccountDAO accountDAO;
    @Autowired private AppDAO appDAO;
    @Autowired private TemplatedMailService mailService;
    @Autowired private CloudOsConfiguration configuration;

    /**
     * Find all accounts. Must be an admin.
     * @param apiKey The session ID
     * @return a List of Accounts
     * @statuscode 403 if caller is not an admin
     */
    @GET
    @ReturnType("java.util.List<cloudos.model.Account>")
    public Response findAll (@HeaderParam(ApiConstants.H_API_KEY) String apiKey) {

        final Account admin = sessionDAO.find(apiKey);
        if (admin == null) return notFound(apiKey);

        // only admins can list all accounts
        if (!admin.isAdmin()) return forbidden();

        return ok(accountDAO.findAll());
    }

    /**
     * Create a new Account. Must be an admin. This also sends a welcome email to the new user.
     * @param apiKey The session ID
     * @param name The name of the new account
     * @param request The AccountRequest
     * @return The Account that was created
     * @statuscode 403 if caller is not an admin
     */
    @PUT
    @Path("/{name}")
    @ReturnType("cloudos.model.Account")
    public Response addAccount(@HeaderParam(ApiConstants.H_API_KEY) String apiKey,
                               @PathParam("name") String name,
                               @Valid AccountRequest request) {

        final Account admin = sessionDAO.find(apiKey);
        if (admin == null) return notFound(apiKey);

        // only admins can create new accounts
        if (!admin.isAdmin()) return forbidden();

        if (request.isTwoFactor()) set2factor(request);

        final Account created;
        try {
            created = accountDAO.create(request);
        } catch (Exception e) {
            log.error("addAccount: error creating account: "+e, e);
            return serverError();
        }

        sendInvitation(admin, created, request.getPassword());

        return ok(created);
    }

    public void sendInvitation(Account admin, Account created, String password) {
        // todo: use the event bus for this?
        // Send welcome email with password and link to login and change it
        final String hostname = configuration.getHostname();
        final TemplatedMail mail = new TemplatedMail()
                .setTemplateName(TemplatedMailService.T_WELCOME)
                .setLocale("en_US") // todo: set this at first-time-setup
                .setFromEmail(admin.getName() + "@" + hostname)
                .setFromName(admin.getFullName())
                .setToEmail(created.getEmail())
                .setToName(created.getName())
                .setParameter(TemplatedMailService.PARAM_ACCOUNT, created)
                .setParameter(TemplatedMailService.PARAM_ADMIN, admin)
                .setParameter(TemplatedMailService.PARAM_HOSTNAME, hostname)
                .setParameter(TemplatedMailService.PARAM_PASSWORD, password);
        try {
            mailService.getMailSender().deliverMessage(mail);

        } catch (Exception e) {
            log.error("addAccount: error sending welcome email: "+e, e);
        }
    }

    /**
     * Update an Account. Caller can only update their own account unless they are an admin.
     * @param apiKey The session ID
     * @param name The name of the account to update
     * @param request The AccountRequest
     * @return The updated Account
     * @statuscode 403 if the caller is not an admin and is attempting to update an Account other than their own
     * @statuscode 404 account not found
     */
    @POST
    @Path("/{name}")
    @ReturnType("cloudos.model.Account")
    public Response update (@HeaderParam(ApiConstants.H_API_KEY) String apiKey,
                            @PathParam("name") String name,
                            @Valid AccountRequest request) {

        final Account account = sessionDAO.find(apiKey);
        if (account == null) return notFound(apiKey);

        // admins can update anyone, others can only update themselves
        if (!account.isAdmin() && !account.getName().equals(name)) {
            return forbidden();
        }

        // if the caller is not an admin, ensure the request is not an admin
        if (!account.isAdmin()) request.setAdmin(false);

        // if caller is an admin suspending a user, ensure they are not suspending themselves
        if (account.isAdmin() && request.isSameName(account) && request.isSuspended()) {
            return invalid("err.admin.cannotSuspendSelf");
        }

        Account found = accountDAO.findByName(name);
        if (found == null) return notFound(name);

        if (!request.isTwoFactor() && found.isTwoFactor()) {
            // they are turning off two-factor auth
            remove2factor(found);
        } else if (request.isTwoFactor() && !found.isTwoFactor()) {
            // they are turning on two-factor auth
            set2factor(request);
        } else if (!request.getMobilePhone().equals(found.getMobilePhone())) {
            // they changed their phone number, remove old auth id and add a new one
            remove2factor(found);
            set2factor(request);
        }

        try {
            found = accountDAO.update(request);
        } catch (Exception e) {
            log.error("Error calling AccountDAO.save: "+e, e);
            return serverError();
        }

        if (request.isSuspended()) {
            sessionDAO.invalidateAllSessions(found.getUuid());
        } else {
            sessionDAO.update(apiKey, account);
        }

        return ok(account);
    }

    private Account set2factor(AccountRequest request) {
        return request.setAuthIdInt(getTwoFactorAuthService().addUser(request.getEmail(), request.getMobilePhone(), request.getMobilePhoneCountryCodeString()));
    }

    private void remove2factor(Account account) { getTwoFactorAuthService().deleteUser(account.getAuthIdInt()); }

    /**
     * Change account password. Caller can only change their own password unless they are an admin.
     * @param apiKey The session ID
     * @param name The name of the account to change
     * @param request The change password request
     * @return The updated account
     */
    @POST
    @Path(EP_CHANGE_PASSWORD)
    @ReturnType("cloudos.model.Account")
    public Response changePassword(@HeaderParam(ApiConstants.H_API_KEY) String apiKey,
                                   @PathParam(PARAM_NAME) String name,
                                   @Valid ChangePasswordRequest request) {

        final Account account = sessionDAO.find(apiKey);
        if (account == null) return notFound(apiKey);

        // non-admins cannot change anyone's password but their own
        final boolean targetIsSelf = account.getName().equals(name);
        if ( !( account.isAdmin() || targetIsSelf) ) return forbidden();

        final Account target = accountDAO.findByName(name);

        try {
            if (account.isAdmin() && !targetIsSelf) {
                accountDAO.setPassword(target, request.getNewPassword());
                if (request.isSendInvite()) {
                    sendInvitation(account, target, request.getNewPassword());
                }
            } else {
                accountDAO.changePassword(target, request.getOldPassword(), request.getNewPassword());
            }

        } catch (AuthenticationException e) {
            return forbidden();

        } catch (Exception e) {
            log.error("Error calling AccountDAO.changePassword: "+e, e);
            return serverError();
        }

        return ok(account);
    }

    /**
     * Find a single Account. Caller can only find their own account unless they are an admin
     * @param apiKey The session ID
     * @param name The name of the account to find
     * @return The Account
     * @statuscode 403 if the caller is not an admin and is attempting to find an Account other than their own
     * @statuscode 404 account not found
     */
    @GET
    @Path("/{name}")
    @ReturnType("cloudos.model.Account")
    public Response find(@HeaderParam(ApiConstants.H_API_KEY) String apiKey,
                         @PathParam("name") String name) {
        long start = System.currentTimeMillis();
        name = name.toLowerCase();

        final Account account = sessionDAO.find(apiKey);
        if (account == null) return notFound(apiKey);

        // non-admins are only allowed to lookup their own account
        if (!account.isAdmin() && !account.getName().equalsIgnoreCase(name)) return forbidden();

        try {
            final Account found = accountDAO.findByName(name);
            if (found == null) return notFound(name);

            if (found.isSuspended() && !account.isAdmin()) {
                // suspended accounts cannot be looked up, except by admins
                return notFound(name);
            }

            return ok(found);

        } catch (Exception e) {
            log.error("Error looking up account: "+e, e);
            return serverError();

        } finally {
            log.info("find executed in "+ TimeUtil.formatDurationFrom(start));
        }
    }

    /**
     * Delete an account. Must be admin.
     * @param apiKey The session ID
     * @param name The name of the account to delete
     * @return Just an HTTP status code
     * @statuscode 403 if the caller is not an admin
     * @statuscode 404 account not found
     */
    @DELETE
    @Path("/{name}")
    @ReturnType("java.lang.Void")
    public Response delete(@HeaderParam(ApiConstants.H_API_KEY) String apiKey,
                           @PathParam("name") String name) {

        final Account admin = sessionDAO.find(apiKey);
        if (admin == null) return notFound(apiKey);
        name = name.toLowerCase();

        // only admins can delete accounts
        if (!admin.isAdmin()) return forbidden();

        // cannot delete your own account or system mailer account
        if (name.equals(admin.getName()) || name.equals(configuration.getSmtp().getUser())) {
            return forbidden();
        }

        Account toDelete = accountDAO.findByName(name);
        if (toDelete != null && toDelete.hasAuthId()) remove2factor(toDelete);

        try {
            accountDAO.delete(name);
        } catch (Exception e) {
            log.error("delete: error deleting account "+name+": "+e, e);
            return serverError();
        }

        return ok(Boolean.TRUE);
    }

}
