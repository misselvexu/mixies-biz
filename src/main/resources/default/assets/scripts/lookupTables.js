function LookupTableInfo(options) {
    this.tableName = options.tableName;
    this.codeCallback = options.codeCallback;
    this.labelFormat = options.labelFormat;
    this.query = options.query || "";


    this._modal = document.getElementById('lookup-modal');

    // Get rid of all previously registered event listeners etc. by re-creating the inner DOM...
    this._modal.innerHTML = this._modal.innerHTML;

    this._queryField = this._modal.querySelector('.query-input');
    this._queryField.value = '';
    this._queryField.setAttribute('placeholder', '');
    this._title = this._modal.querySelector('.modal-title');
    this._title.textContent = this.tableName;
    this._description = this._modal.querySelector('.lookuptable-description');
    this._output = this._modal.querySelector('.entries');

    const me = this;

    this.entriesToSkip = 0;
    this._paginationLeft = this._modal.querySelector('.pagination-left');
    this._paginationLeft.addEventListener('click', function (event) {
        if (this.dataset.skip != null) {
            me.entriesToSkip = this.dataset.skip;
            me.reload();
        }
        event.preventDefault();
    });
    this._paginationRight = this._modal.querySelector('.pagination-right');
    this._paginationRight.addEventListener('click', function (event) {
        if (this.dataset.skip != null) {
            me.entriesToSkip = this.dataset.skip;
            me.reload();
        }
        event.preventDefault();
    });
    this._paginationInfo = this._modal.querySelector('.pagination-info');


    // Add a key-listener with some easing...
    let timeout = -1;
    this._queryField.addEventListener('keyup', function () {
        me.entriesToSkip = 0;
        clearTimeout(timeout);
        timeout = setTimeout(function () {
            me.reload();
        }, 100);
    });

    // Load data and show modal...
    this.reload().then(function () {
        $(me._modal).modal('show');
        $(me._modal).on('shown.bs.modal', function () {
            me._queryField.focus();
        });
    });
}

LookupTableInfo.prototype.ENTRY_TEMPLATE = '<td>' +
    '<div class="d-flex flex-row">' +
    '   <div class="mr-auto">' +
    '       <a class="font-weight-bold code-link" data-label="{{label}}" data-code="{{code}}">{{name}}</a>' +
    '       {{#showCode}} ({{code}}){{/showCode}}' +
    '   </div>' +
    '   {{#source}}' +
    '   <div class="text-small cursor-pointer toggle-source-link d-none"><span class="icon"><i class="fa fa-plus"></i></span> <a class="toggle-source" href="#"> Source</a></div>' +
    '   {{/source}}' +
    '</div>' +
    '{{#description}}<div class="text-small text-muted mt-2">{{description}}</div>{{/description}}' +
    '{{#source}}' +
    '   <div class="whitespace-pre text-monospace text-small left-border border-sirius-blue-dark pl-2 pr-2 mt-1 source d-none">{{source}}</div>' +
    '{{/source}}' +
    '</td>'

LookupTableInfo.prototype.reload = function () {
    const me = this;
    return sirius.getJSON('/system/lookuptable/info/' + this.tableName, {
        query: this._queryField.value,
        skip: this.entriesToSkip,
        labelFormat: this.labelFormat
    }).then(function (response) {
        me._title.textContent = response.title;
        me._description.innerHTML = response.description;
        me._queryField.setAttribute('placeholder', response.searchPlaceholder);
        if (response.prevSkip != null) {
            me._paginationLeft.dataset.skip = response.prevSkip;
            me._paginationLeft.classList.remove('disabled');
        } else if (!me._paginationLeft.classList.contains('disabled')) {
            me._paginationLeft.dataset.skip = "";
            me._paginationLeft.classList.add('disabled');
        }
        if (response.nextSkip != null) {
            me._paginationRight.dataset.skip = response.nextSkip;
            me._paginationRight.classList.remove('disabled');
        } else if (!me._paginationRight.classList.contains('disabled')) {
            me._paginationRight.dataset.skip = "";
            me._paginationRight.classList.add('disabled');
        }
        me._paginationInfo.textContent = response.paginationInfo;
        me._output.innerHTML = '';

        for (let i = 0; i < response.entries.length; i++) {
            const _entry = document.createElement('TR');
            _entry.innerHTML = Mustache.render(me.ENTRY_TEMPLATE, response.entries[i]);

            // Enable codeCallback if present...
            if (typeof me.codeCallback == 'function') {
                const _codeLink = _entry.querySelector('.code-link');
                _codeLink.setAttribute("href", "#");
                _codeLink.addEventListener('click', function (e) {
                    e.preventDefault();
                    $(me._modal).modal('hide');
                    me.codeCallback(this.dataset.code, this.dataset.label);
                });
            }

            // Activate source link if present...
            let _toggleSource = _entry.querySelector('.toggle-source');
            if (_toggleSource != null) {
                _toggleSource.addEventListener('click', function (e) {
                    e.preventDefault();
                    const _source = _entry.querySelector('.source');
                    const _icon = _entry.querySelector('.icon');
                    if (_source.classList.contains('d-none')) {
                        _source.classList.remove('d-none');
                        _icon.innerHTML = '<i class="fa fa-minus"></i>';
                    } else {
                        _source.classList.add('d-none');
                        _icon.innerHTML = '<i class="fa fa-plus"></i>';
                    }
                });
            }
            const _toggleSourceLink = _entry.querySelector('.toggle-source-link');
            if (_toggleSourceLink != null) {
                _entry.addEventListener('mouseenter', function () {
                    if (_toggleSourceLink.classList.contains('d-none')) {
                        _toggleSourceLink.classList.remove('d-none');
                    }
                });
                _entry.addEventListener('mouseleave', function () {
                    if (!_toggleSourceLink.classList.contains('d-none')) {
                        _toggleSourceLink.classList.add('d-none');
                    }
                });
            }
            me._output.appendChild(_entry);
        }
    });
};

function openLookupTable(tableName) {
    new LookupTableInfo({tableName: tableName});
}