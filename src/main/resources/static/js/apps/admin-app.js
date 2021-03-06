App = Ember.Application.create({
    // for debugging, disable in prod
    LOG_TRANSITIONS: true,
    LOG_ACTIVE_GENERATION: true
});

App.Router.map(function() {
    this.resource('logout');
//  this.resource('apps');
    this.resource('appstore');
    this.resource('tasks', { path: '/task/:task_id' });
    this.resource('email', function () {
        this.route('domains');
        this.resource('aliases', function() {
            this.route('new');
        });
        this.resource('alias', { path: '/alias/:alias_name' });
    });
    this.resource('accounts');
    this.resource('addAccount');
    this.resource('manageAccount', { path: '/accounts/:name' });

    this.resource('security', function() {
        this.resource('certs', function() {
            this.route('new');
        });
        this.resource('cert', { path: '/cert/:cert_name' });
    });
//  this.resource('addCloud', { path: '/add_cloud/:cloud_type' });
//  this.resource('configCloud', { path: '/cloud/:cloud_name' });
});

App.ApplicationRoute = Ember.Route.extend({
    model: function() {
        return {
            cloudos_session: sessionStorage.getItem('cloudos_session'),
            cloudos_account: CloudOs.account()
        };
    },
    setupController: function(controller, model) {

        // is HTML5 storage even supported?
        if (typeof(Storage) == "undefined") {
            alert('Your browser is not supported. Please use Firefox, Chrome, Safari 4+, or IE8+');
            return;
        }

        // do we have an API token?
        if (!model.cloudos_session) {
            window.location.replace('/index.html');
            return;
        }

        // is the token valid?
        var account = Api.account_for_token(model.cloudos_session);
        if (!account || !account.admin) {
            sessionStorage.removeItem('cloudos_session');
            sessionStorage.removeItem('cloudos_account');
            window.location.replace('/index.html');
            return;
        }

        CloudOs.set_account(account);

        this.transitionTo('accounts');
    }
});

App.IndexRoute = App.ApplicationRoute;

App.LogoutRoute = Ember.Route.extend({
    setupController: function(controller, model) {
        sessionStorage.clear();
        localStorage.clear();
        window.location.replace('/admin.html');
    }
});

App.ApplicationController = Ember.ObjectController.extend({

    cloudos_session: function () {
        return sessionStorage.getItem('cloudos_session');
    }.property('cloudos_session'),

    cloudos_account: function () {
        return CloudOs.account();
    }.property('cloudos_account')

});

App.AppstoreRoute = Ember.Route.extend({
    model: function() {
        var page = {"pageSize": "10", "pageNumber": "1"};
        var apps = Api.find_apps(page);
        return { "apps": apps };
    }
});

App.AppstoreController = Ember.ObjectController.extend({
    appUrl: '',
    actions: {
        installFromUrl: function () {
            var task_id = Api.install_app_from_url(this.get('appUrl'));
            if (task_id && task_id.uuid) {
                this.transitionTo('tasks', task_id.uuid);
            }
        }
    }
});

App.TasksRoute = Ember.Route.extend({
    model: function(model) {
        return { task_id: model.task_id,
            result: Api.get_task_results(model.task_id) };
    }
});

App.AccountsRoute = Ember.Route.extend({
    model: function () {
        return {
            'accounts': Api.list_accounts()
        };
    }
});

App.AccountsController = Ember.ObjectController.extend({
});

App.newAccountModel = function () {
    return {
        accountName: '',
        recoveryEmail: '',
        mobilePhone: '',
        admin: false
    };
}

App.AddAccountRoute = Ember.Route.extend({
    model: App.newAccountModel
});

App.AddAccountController = Ember.ObjectController.extend({
    actions: {
        doCreateAccount: function () {
            // API uses camelcase, ember uses snake case
            account = {
                name: this.get('accountName'),
                recoveryEmail: this.get('recoveryEmail'),
                mobilePhone: this.get('mobilePhone'),
                admin: this.get('admin')
            };
            if (Api.add_account(account)) {
                this.transitionTo('accounts');
            }
        }
    }
});

App.ManageAccountRoute = Ember.Route.extend({
    model: function (params) {
        return Api.find_account(params.name) || App.newAccountModel();
    }
});

App.ManageAccountController = Ember.ObjectController.extend({
    actions: {
        'doUpdateAccount': function () {
            account = {
                name: this.get('accountName'),
                recoveryEmail: this.get('recoveryEmail'),
                mobilePhone: this.get('mobilePhone'),
                admin: this.get('admin')
            };
            if (Api.update_account(account)) {
                this.transitionTo('accounts');
            }
        },
        'doDeleteAccount': function (name) {
            if (Api.delete_account(name)) {
                this.transitionTo('accounts');
            }
        }
    }
});

App.EmailDomainsRoute = Ember.Route.extend({
    model: function () {
        return {
            'mxrecord': Api.cloudos_configuration().mxrecord,
            'domains': Api.list_email_domains()
        }
    }
});

App.EmailDomainsController = Ember.ObjectController.extend({
    actions: {
        doAddDomain: function () {
            var name = this.get('domain');
            if (!Api.add_email_domain(name)) {
                alert('error adding domain: ' + name);
            }
        },
        doRemoveDomain: function (name) {
            if (!Api.remove_email_domain(name)) {
                alert('error removing domain: ' + name);
            }
        }
    }
});

App.AliasesRoute = Ember.Route.extend({
    model: function () {
        return Api.find_email_aliases();
    }
});

App.AliasesIndexController = Ember.ObjectController.extend({
    actions: {
        doRemoveAlias: function (name) {
            if (!Api.remove_email_alias(name)) {
                alert('error removing alias: '+name);
            }
        }
    }
});

App.AliasesNewController = Ember.ObjectController.extend({
    actions: {
        doAddAlias: function () {
            var name = this.get('aliasName');
            var recipients = [];
            var recipient_names = this.get('recipients').split(",");
            for (var i=0; i<recipient_names.length; i++) {
                recipients.push(recipient_names[i].trim());
            }
            if (!Api.add_email_alias({ 'name': name, 'recipients': recipients })) {
                alert('error adding alias: '+name);
            }
        }
    }
});

App.AliasRoute = Ember.Route.extend({
    model: function (params) {
        var alias = Api.find_email_alias(params['alias_name']);
        var recipients = [];
        for (var i=0; i<alias.members.length; i++) {
            recipients.push(alias.members[i].name);
        }
        return {
            'aliasName': params['alias_name'],
            'recipients': recipients
        }
    }
});

App.AliasController = Ember.ObjectController.extend({
    actions: {
        doEditAlias: function () {
            var name = this.get('aliasName');
            var recipients = [];
            var recipient_names = this.get('recipients').split(",");
            for (var i=0; i<recipient_names.length; i++) {
                recipients.push(recipient_names[i].trim());
            }
            if (!Api.edit_email_alias({ 'name': name, 'recipients': recipients })) {
                alert('error editing alias: '+name);
            }
        }
    }
});

App.SecurityRoute = Ember.Route.extend({
    setupController: function(controller, model) {
        this.transitionTo('certs.index');
    }
});

App.CertsRoute = Ember.Route.extend({
    model: function () {
        return Api.find_ssl_certs();
    }
});

App.CertsIndexController = Ember.ObjectController.extend({
    actions: {
        doRemoveCert: function (name) {
            if (!Api.remove_ssl_cert(name)) {
                alert('error removing cert: '+name);
            }
        }
    }
});

App.CertsNewController = Ember.ObjectController.extend({
    actions: {
        doAddCert: function () {
            var name = this.get('certName');
            var description = this.get('description');
            var pem = this.get('pem');
            var key = this.get('key');
            var cert = {
                'name': name,
                'description': description,
                'pem': pem,
                'key': key
            };
            if (!Api.add_ssl_cert(cert)) {
                alert('error adding cert: '+name);
            }
        }
    }
});

App.CertRoute = Ember.Route.extend({
    model: function (params) {
        var cert = Api.find_ssl_cert(params['cert_name']);
        return  cert;
    }
});

App.CertController = App.CertsNewController;

Ember.Handlebars.helper('cloud-type-field', function(cloudType, field) {

    var cloudTypeTranslations = Em.I18n.translations['cloudTypes'][cloudType];
    if (!cloudTypeTranslations) return '??undefined translation: cloudTypes.'+cloudType+'.'+field;

    var name = cloudTypeTranslations[field];
    if (!name) return '??undefined translation: cloudTypes.'+cloudType+'.'+field;

    return new Handlebars.SafeString(name);
});

Ember.Handlebars.helper('cloud-option-name', function(cloudType, optionName) {
    var cloudTypeTranslations = Em.I18n.translations['cloudTypes'][cloudType];
    if (!cloudTypeTranslations) return '??undefined translation: cloudTypes.'+cloudType+'.'+field;

    var name = cloudTypeTranslations['options'][optionName];
    if (!name) return '??undefined translation: cloudTypes.'+cloudType+'.options.'+optionName;

    return name;
});

Ember.Handlebars.helper('app-usage', function(usage) {
    var appUsage = Em.I18n.translations['appUsage'][usage];
    if (!appUsage) return '??undefined translation: appUsage.'+usage;
    return appUsage;
});

Ember.Handlebars.helper('task-description', function(result) {
    var action = Em.I18n.translations['task'][result.actionMessageKey];
    if (!action) return '??undefined translation: task.'+result.actionMessageKey;
    if (result.target) action += ": " + result.target;
    return action;
});

Ember.Handlebars.helper('task-event', function(key) {
    var value = Em.I18n.translations['task']['events'][key];
    if (!value) return '??undefined translation: task.events.'+key;
    return value;
});
