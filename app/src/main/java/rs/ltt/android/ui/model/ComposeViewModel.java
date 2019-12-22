/*
 * Copyright 2019 Daniel Gultsch
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package rs.ltt.android.ui.model;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.google.common.base.Strings;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import org.checkerframework.checker.nullness.compatqual.NullableDecl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import rs.ltt.android.R;
import rs.ltt.android.entity.AccountWithCredentials;
import rs.ltt.android.entity.EditableEmail;
import rs.ltt.android.entity.IdentityWithNameAndEmail;
import rs.ltt.android.repository.ComposeRepository;
import rs.ltt.android.repository.MainRepository;
import rs.ltt.android.ui.ComposeAction;
import rs.ltt.android.util.Event;
import rs.ltt.jmap.common.entity.EmailAddress;
import rs.ltt.jmap.mua.util.EmailAddressUtil;

public class ComposeViewModel extends AndroidViewModel {

    private static final Logger LOGGER = LoggerFactory.getLogger(ComposeViewModel.class);

    private final ComposeRepository repository;
    private final ComposeAction composeAction;
    private final ListenableFuture<EditableEmail> email;

    private final MutableLiveData<Event<String>> errorMessage = new MutableLiveData<>();

    private final MutableLiveData<Integer> selectedIdentityPosition = new MutableLiveData<>();
    private final MutableLiveData<String> to = new MutableLiveData<>();
    private final MutableLiveData<String> subject = new MutableLiveData<>();
    private final MutableLiveData<String> body = new MutableLiveData<>();
    private final LiveData<List<IdentityWithNameAndEmail>> identities;

    private boolean draftHasBeenHandled = false;

    public ComposeViewModel(@NonNull final Application application,
                            final Long id,
                            final boolean freshStart,
                            final ComposeAction composeAction,
                            final String emailId) {
        super(application);
        this.composeAction = composeAction;
        final MainRepository mainRepository = new MainRepository(application);
        final ListenableFuture<AccountWithCredentials> account = mainRepository.getAccount(id);
        this.repository = new ComposeRepository(application, account);
        this.identities = this.repository.getIdentities();
        if (composeAction == ComposeAction.NEW) {
            this.email = null;
        } else {
            this.email = this.repository.getEditableEmail(emailId);
        }
        if (freshStart && this.email != null) {
            initializeWithEmail();
        }
    }

    public LiveData<Event<String>> getErrorMessage() {
        return this.errorMessage;
    }

    public MutableLiveData<String> getTo() {
        return this.to;
    }

    public MutableLiveData<String> getBody() {
        return this.body;
    }

    public MutableLiveData<String> getSubject() {
        return this.subject;
    }

    public LiveData<List<IdentityWithNameAndEmail>> getIdentities() {
        return this.identities;
    }

    public MutableLiveData<Integer> getSelectedIdentityPosition() {
        return this.selectedIdentityPosition;
    }

    public boolean discard() {
        final EditableEmail email = getEmail();
        final boolean isOnlyEmailInThread = email == null || repository.discard(email);
        this.draftHasBeenHandled = true;
        return isOnlyEmailInThread;
    }

    public boolean send() {
        final IdentityWithNameAndEmail identity = getIdentity();
        if (identity == null) {
            postErrorMessage(R.string.select_sender);
            return false;
        }
        final Collection<EmailAddress> toEmailAddresses = EmailAddressUtil.parse(
                Strings.nullToEmpty(to.getValue())
        );
        if (toEmailAddresses.size() <= 0) {
            postErrorMessage(R.string.add_at_least_one_recipient);
            return false;
        }
        for (EmailAddress emailAddress : toEmailAddresses) {
            if (EmailAddressUtil.isValid(emailAddress)) {
                continue;
            }
            postErrorMessage(R.string.the_address_x_is_invalid, emailAddress.getEmail());
            return false;
        }
        LOGGER.info("sending with identity {}", identity.getId());
        this.repository.sendEmail(identity, toEmailAddresses, subject.getValue(), body.getValue());
        this.draftHasBeenHandled = true;
        return true;
    }

    public UUID saveDraft() {
        if (this.draftHasBeenHandled) {
            LOGGER.info("Not storing as draft. Email has already been stored.");
            return null;
        }
        final IdentityWithNameAndEmail identity = getIdentity();
        if (identity == null) {
            LOGGER.info("Not storing draft. No identity has been selected");
            return null;
        }
        final Collection<EmailAddress> to = EmailAddressUtil.parse(Strings.nullToEmpty(this.to.getValue()));
        final String subject = Strings.nullToEmpty(this.subject.getValue());
        final String body = Strings.nullToEmpty(this.body.getValue());
        if (to.isEmpty() && subject.trim().isEmpty() && body.trim().isEmpty()) {
            LOGGER.info("not storing draft. To, subject and body are empty.");
            return null;
        }
        final EditableEmail email = getEmail();
        if (this.composeAction == ComposeAction.EDIT_DRAFT) {
            if (email != null
                    && EmailAddressUtil.equalCollections(email.getTo(), to)
                    && subject.equals(email.subject)
                    && body.equals(email.getText())) {
                LOGGER.info("Not storing draft. Nothing has been changed");
                return null;
            }
        }
        LOGGER.info("Saving draft");
        final String discard;
        if (this.composeAction == ComposeAction.EDIT_DRAFT) {
            discard = email != null ? email.id : null;
            LOGGER.info("Requesting to delete previous draft={}", discard);
        } else {
            discard = null;
        }
        final UUID uuid = this.repository.saveDraft(identity, to, subject, body, discard);
        this.draftHasBeenHandled = true;
        return uuid;
    }

    private IdentityWithNameAndEmail getIdentity() {
        final List<IdentityWithNameAndEmail> identities = this.identities.getValue();
        final Integer selectedIdentity = this.selectedIdentityPosition.getValue();
        if (identities != null && selectedIdentity != null && selectedIdentity < identities.size()) {
            return identities.get(selectedIdentity);
        }
        return null;
    }

    private void postErrorMessage(@StringRes final int res, final Object... objects) {
        this.errorMessage.postValue(
                new Event<>(getApplication().getString(res, objects))
        );
    }

    private EditableEmail getEmail() {
        if (this.email != null && this.email.isDone()) {
            try {
                return this.email.get();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private void initializeWithEmail() {
        Futures.addCallback(this.email, new FutureCallback<EditableEmail>() {
            @Override
            public void onSuccess(@NullableDecl EditableEmail result) {
                initializeWithEmail(result);
            }

            @Override
            public void onFailure(Throwable t) {

            }
        }, MoreExecutors.directExecutor());
    }

    private void initializeWithEmail(final EditableEmail email) {
        to.postValue(EmailAddressUtil.toHeaderValue(email.getTo()));
        subject.postValue(email.subject);
        body.postValue(email.getText());
    }
}
