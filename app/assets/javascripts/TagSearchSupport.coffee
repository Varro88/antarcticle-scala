jQuery(=>
  $(document).ready(=>
    input = $('#search-input')
    currentFilter = input.attr('value').split(',')
    # highlight all tags we've searched for before
    $("span.tm-tag.tm-tag-default").filter(() ->
      currentFilter.indexOf($(this).text().trim()) >= 0
    )
    .removeClass("tm-tag-default")
    .addClass("tm-tag-info")
    # set up tags and tag suggestion list for input fields
    tagApi = input.tagsManager({
      tagClass: "tm-tag tm-tag-info"
      hiddenTagListName: "tags",
      tagsContainer: '.tag-container',
      onlyTagList: true
    });
    input.typeahead({
      limit: 10,
      prefetch: input.attr('data-url'),
    # filters autocomplete suggestions to exclude already selected tags
      suggestionFilter: (list) ->
        list.filter((elem) ->
          $.inArray(elem.value, tagApi.tagsManager("tags")) == -1
        )
    }).on('typeahead:selected', (e, d) =>
      tagApi.tagsManager("pushTag", d.value)
      input.closest('form').submit()
    )
    # if we have something in the search field on page load - make tags from it
    tagApi.tagsManager("pushTag", tag) for tag in currentFilter
    $('.tt-hint').addClass('form-control');
    # control tag search form submit behavior
    $('.tm-tag-remove').click(() ->
      input.closest('form').submit()
    )
    input.closest('form').on 'submit', (e) ->
      inputData = input.val()
      if (inputData.trim() != "")
        tagApi.tagsManager("pushTag", inputData)
      if ($('.tag-container span').length == 0)
        document.location = document.location.toString().split('?')[0]
        e.preventDefault()
        false
      else
        true
  )
)