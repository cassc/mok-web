"use strict"

var React = require('react');
var ColorPicker = require('react-color');

class Component extends React.Component {

    constructor() {
        super();
        this.state = {
            displayColorPicker: false,
        };
        this.handleClick = this.handleClick.bind(this);
    }

    handleClick() {
        this.setState({ displayColorPicker: !this.state.displayColorPicker });
    }

    render() {
        return (
                <div>
                <button onClick={ this.handleClick }>Pick Color</button>
                <ColorPicker display={ this.state.displayColorPicker } type="sketch" />
                </div>
        );
    }
}
