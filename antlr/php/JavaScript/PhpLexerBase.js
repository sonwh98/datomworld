/*
PHP grammar.
The MIT License (MIT).
Copyright (c) 2015-2019, Ivan Kochurkin (kvanttt@gmail.com), Positive Technologies.
Copyright (c) 2019, Thierry Marianne (thierry.marianne@weaving-the-web.org)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
*/

// Node port of the pinned Java helper, including the JVM profile's
// positioned heredoc terminator. Constants come from the generated subclass,
// avoiding a circular import of PhpLexer.
import antlr4Module from 'antlr4';
const antlr4 = antlr4Module.default || antlr4Module;

export default class PhpLexerBase extends antlr4.Lexer {
    constructor(input) {
        super(input);
        this.AspTags = true;
        this._scriptTag = false;
        this._styleTag = false;
        this._phpScript = false;
        this._insideString = false;
        this._heredocLineStart = false;
        this._prevTokenType = 0;
        this._htmlNameText = null;
        this._heredocIdentifier = null;
    }

    nextToken() {
        const T = this.constructor;
        if (this._mode === T.HereDoc && this._heredocLineStart) {
            const end = this.HeredocEndToken();
            if (end !== null) return end;
        }
        const token = super.nextToken();
        if (token.type === T.PHPEnd || token.type === T.PHPEndSingleLineComment) {
            if (this._mode === T.SingleLineCommentMode) this.popMode();
            this.popMode();
            if (token.text === '</script>') {
                this._phpScript = false;
                token.type = T.HtmlScriptClose;
            } else if ([T.SemiColon, T.Colon, T.OpenCurlyBracket,
                        T.CloseCurlyBracket].includes(this._prevTokenType)) {
                token.channel = T.SkipChannel;
            } else {
                token.type = T.SemiColon;
            }
        } else if (token.type === T.HtmlName) {
            this._htmlNameText = token.text;
        } else if (token.type === T.HtmlDoubleQuoteString) {
            if (token.text === 'php' && this._htmlNameText === 'language') {
                this._phpScript = true;
            }
        } else if (this._mode === T.HereDoc) {
            if (token.type === T.StartHereDoc || token.type === T.StartNowDoc) {
                this._heredocIdentifier = token.text.substring(3).trim().replace(/['"]/g, '');
                this._heredocLineStart = false;
            } else if (token.type === T.HereDocText) {
                this._heredocLineStart = true;
            }
        } else if (this._mode === T.PHP && this._channel !== T.HIDDEN) {
            this._prevTokenType = token.type;
        }
        return token;
    }

    HeredocEndToken() {
        const id = Array.from(this._heredocIdentifier, c => c.codePointAt(0));
        let p = 1;
        while (this._input.LA(p) === 32 || this._input.LA(p) === 9) p++;
        for (const c of id) {
            if (this._input.LA(p++) !== c) return null;
        }
        const c = this._input.LA(p);
        if (c === 95 || c >= 128 || c >= 48 && c <= 57 ||
            c >= 65 && c <= 90 || c >= 97 && c <= 122) return null;
        const start = this._input.index;
        const line = this.line;
        const column = this.column;
        for (let k = 1; k < p; k++) this._interp.consume(this._input);
        this.popMode();
        const token = new antlr4.CommonToken(this._tokenFactorySourcePair,
            this.constructor.HereDocEnd, antlr4.Token.DEFAULT_CHANNEL,
            start, this._input.index - 1);
        token.line = line;
        token.column = column;
        return token;
    }

    IsNewLineOrStart(pos) {
        const c = this._input.LA(pos);
        return c <= 0 || c === 13 || c === 10;
    }
    PushModeOnHtmlClose() {
        const T = this.constructor;
        this.popMode();
        if (this._scriptTag) {
            this.pushMode(this._phpScript ? T.PHP : T.SCRIPT);
            this._scriptTag = false;
        } else if (this._styleTag) {
            this.pushMode(T.STYLE);
            this._styleTag = false;
        }
    }
    HasAspTags() { return this.AspTags; }
    HasPhpScriptTag() { return this._phpScript; }
    PopModeOnCurlyBracketClose() {
        if (this._insideString) {
            this._insideString = false;
            this._channel = this.constructor.SkipChannel;
            this.popMode();
        }
    }
    ShouldPushHereDocMode(pos) {
        return this._input.LA(pos) === 13 || this._input.LA(pos) === 10;
    }
    IsCurlyDollar(pos) { return this._input.LA(pos) === 36; }
    SetInsideString() { this._insideString = true; }
}
