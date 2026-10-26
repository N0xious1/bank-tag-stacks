# Bank Tag Stacks

A RuneLite plugin that lets you stack Bank Tags tabs on top of each other, like folders.

1. Click a stack tab in the bank's tag sidebar
2. The tags inside it fan out to the right in a row
3. Click one of them
4. That bank tag opens (with its layout, if it has one)

Needs the core **Bank Tags** plugin with "Use Tag Tabs" on.

## Using it

|Where|Right-click option|What it does|
|-|-|-|
|Normal tag tab|**Add to stack**|Asks for a stack name. A new name makes a new stack tab in that spot (it borrows the tag's icon); an existing name adds the tag to that stack.|
|Stack tab|**Unstack**|Puts every tag in the stack back in the sidebar where the stack was, and removes the stack tab.|
|Tag in the open row|**Remove from stack**|Puts that one tag back in the sidebar just under the stack. The stack is removed when its last tag leaves.|

* **Drag and drop:** drag any tag tab onto an existing stack tab to add it to that stack. The stack tab lights up green with "Add to …" while you're over it. Dragging a stack onto another stack still just reorders them. Turn this off with **Drag tabs into stacks** in the plugin settings.
* Left-click a stack tab to open or close its row. Clicking anywhere else also closes it.
* Clicking the tag that's already open closes it, same as Bank Tags.
* If the bank isn't wide enough the row wraps onto a second line.
* Stack tabs get a small arrow and a count; the stack's tab is outlined while one of its tags is open (both can be turned off in the plugin settings).
* Change a stack's icon with Bank Tags' own **Change icon** option on the stack tab.

## How it works

Bank Tags draws its sidebar from the saved `banktags.tagtabs` list. Adding a tag to a stack just takes it out of that list and puts a stack tab in its place. The tag itself, its items, icon and layout are never touched, and the stack definitions are saved in this plugin's own config group.

If you turn the plugin off, every stacked tag is put back in the sidebar so nothing goes missing. Turning it back on re-collapses them.

## Things to know

* Don't rename a stack tab with Bank Tags' **Rename** option, and don't drag items onto a stack tab (that tags the items with the stack's name). Use Unstack and re-stack instead.
* If you delete a stack tab with Bank Tags' **Delete tag tab**, its tags come back as normal tabs the next time you open the bank.
* Stacks are one level deep (no stacks inside stacks).

## 

